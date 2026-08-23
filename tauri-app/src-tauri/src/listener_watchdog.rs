//! Self-healing for the network listeners.
//!
//! Windows sleep/hibernate cycles can invalidate the listening sockets of a
//! still-running process: after waking up the OS refuses inbound connections
//! even though the process holds its (now dead) listener, so phones see
//! "connection refused" until the server is restarted by hand. The watchdog
//! detects exactly that state and orders the TCP/UDP tasks to rebuild their
//! sockets, mirroring what a manual stop/start cycle does.

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;
use tokio::sync::{watch, Mutex};
use tokio_util::sync::CancellationToken;

use crate::network::NetworkManager;

const PROBE_INTERVAL: Duration = Duration::from_secs(5);
const PROBE_TIMEOUT: Duration = Duration::from_millis(1500);
const FAILURE_THRESHOLD: u32 = 2;
// Pause between listener/socket rebuild attempts when the watchdog ordered a
// rebuild but the port cannot be rebound yet. Shared with the TCP/UDP tasks so
// both halves of one rebuild stay in lockstep.
pub const REBIND_RETRY_INTERVAL: Duration = Duration::from_secs(1);
// How long (per rebuild order) to keep checking whether the rebuilt listeners
// answer again before giving up on refreshing mDNS for this cycle.
const RECOVERY_CONFIRM_ATTEMPTS: u32 = 10;

/// Watchdog tuning. Production values come from [`Self::for_bind`]; tests
/// inject tiny intervals to exercise the failure path quickly.
#[derive(Clone, Copy, Debug)]
pub struct ListenerWatchdogConfig {
    pub probe_target: SocketAddr,
    pub probe_interval: Duration,
    pub probe_timeout: Duration,
    pub failure_threshold: u32,
}

impl ListenerWatchdogConfig {
    pub fn for_bind(bind_address: &str, port: u16) -> Option<Self> {
        Some(Self {
            probe_target: resolve_probe_address(bind_address, port)?,
            probe_interval: PROBE_INTERVAL,
            probe_timeout: PROBE_TIMEOUT,
            failure_threshold: FAILURE_THRESHOLD,
        })
    }
}

pub type MdnsSlot = Arc<Mutex<Option<NetworkManager>>>;

pub struct ListenerWatchdogDeps {
    /// Generation counter bumped whenever the TCP/UDP listeners must be rebuilt.
    pub rebind: watch::Sender<u64>,
    pub mdns: MdnsSlot,
    pub mdns_port: u16,
    pub mdns_bind: String,
}

/// Resolve the address used to verify the control listener from inside the host.
///
/// Wildcard binds are probed over loopback so the check never depends on a
/// physical adapter being up; explicit binds are probed on their own address.
/// The returned IP is also the source address such a probe connects from.
pub fn resolve_probe_address(bind_address: &str, port: u16) -> Option<SocketAddr> {
    let ip: IpAddr = if bind_address == "0.0.0.0" || bind_address.is_empty() {
        IpAddr::from([127, 0, 0, 1])
    } else {
        bind_address.parse().ok()?
    };
    Some(SocketAddr::new(ip, port))
}

/// Sleep for `duration`; returns `true` when cancellation won the race.
pub async fn wait_or_cancel(duration: Duration, cancel_token: &CancellationToken) -> bool {
    tokio::select! {
        _ = cancel_token.cancelled() => true,
        _ = tokio::time::sleep(duration) => false,
    }
}

/// Probe briefly after ordering a rebuild; mDNS is only refreshed when the
/// control listener actually serves again within the confirmation window, so a
/// persistently dead bind cannot churn the daemon on every watchdog cycle.
async fn confirm_recovery(
    probe_target: SocketAddr,
    probe_timeout: Duration,
    attempts: u32,
    retry_interval: Duration,
    cancel_token: &CancellationToken,
) -> bool {
    for _ in 0..attempts {
        if cancel_token.is_cancelled() {
            return false;
        }
        if probe_listener(probe_target, probe_timeout).await {
            return true;
        }
        if wait_or_cancel(retry_interval, cancel_token).await {
            return false;
        }
    }
    false
}

/// Open one throwaway connection to prove the listener still accepts.
///
/// Closing immediately makes the server treat this as a failed client
/// handshake, which publishes no session state and frees the slot again.
pub async fn probe_listener(target: SocketAddr, timeout_duration: Duration) -> bool {
    match tokio::time::timeout(timeout_duration, TcpStream::connect(target)).await {
        Ok(Ok(mut stream)) => {
            let _ = stream.shutdown().await;
            true
        }
        _ => false,
    }
}

/// Periodically verify the control listener is alive and order a rebuild when
/// it stops answering locally. Exits when `cancel_token` is cancelled.
///
/// A `None` config means probing is impossible (unparsable bind address); the
/// task then parks until shutdown so the rebind channel keeps its sender alive.
pub async fn run(
    config: Option<ListenerWatchdogConfig>,
    deps: ListenerWatchdogDeps,
    cancel_token: CancellationToken,
) {
    let Some(config) = config else {
        log::warn!(target: "watchdog", "listener watchdog disabled: probe address unavailable");
        cancel_token.cancelled().await;
        return;
    };

    let mut consecutive_failures: u32 = 0;
    // The first tick fires immediately, validating the freshly bound listeners
    // right after startup; later ticks follow the configured interval.
    let mut probe_tick = tokio::time::interval(config.probe_interval);
    probe_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => break,
            _ = probe_tick.tick() => {
                if probe_listener(config.probe_target, config.probe_timeout).await {
                    consecutive_failures = 0;
                    continue;
                }
                consecutive_failures += 1;
                if consecutive_failures < config.failure_threshold {
                    log::debug!(
                        target: "watchdog",
                        "local probe of {} failed ({}/{})",
                        config.probe_target,
                        consecutive_failures,
                        config.failure_threshold
                    );
                    continue;
                }
                consecutive_failures = 0;
                log::warn!(
                    target: "watchdog",
                    "control listener {} no longer reachable locally; rebuilding network listeners",
                    config.probe_target
                );
                let generation = *deps.rebind.borrow();
                deps.rebind.send_replace(generation.wrapping_add(1));
                // Refresh mDNS only once the rebuilt listeners actually answer
                // again, so a persistently dead bind cannot churn the daemon on
                // every watchdog cycle.
                if confirm_recovery(
                    config.probe_target,
                    config.probe_timeout,
                    RECOVERY_CONFIRM_ATTEMPTS,
                    REBIND_RETRY_INTERVAL,
                    &cancel_token,
                )
                .await
                {
                    reregister_mdns(&deps).await;
                } else {
                    log::warn!(
                        target: "watchdog",
                        "listener still unreachable after rebuild window; skipping mDNS refresh"
                    );
                }
            }
        }
    }
    log::info!(target: "watchdog", "listener watchdog stopped");
}

/// Refresh the mDNS registration alongside a listener rebuild so discovery and
/// connectivity stay in sync after a sleep/resume cycle.
async fn reregister_mdns(deps: &ListenerWatchdogDeps) {
    let mut mdns = deps.mdns.lock().await;
    // Nothing to refresh when registration never succeeded or was already
    // taken down by a concurrent stop.
    let Some(previous) = mdns.take() else {
        return;
    };
    previous.stop_mdns();
    let port = deps.mdns_port;
    let bind_address = deps.mdns_bind.clone();
    // start_mdns performs blocking system calls (daemon thread spawn, adapter
    // enumeration); run them off the async executor. The mutex stays held so a
    // concurrent stop cannot interleave behind us and resurrect the daemon.
    // The error is flattened to String because Box<dyn Error> is not Send.
    match tokio::task::spawn_blocking(move || {
        NetworkManager::start_mdns(port, &bind_address).map_err(|e| e.to_string())
    })
    .await
    {
        Ok(Ok(manager)) => {
            log::info!(target: "watchdog", "mDNS service re-registered");
            *mdns = Some(manager);
        }
        Ok(Err(error)) => {
            log::warn!(target: "watchdog", "mDNS re-registration failed: {}", error)
        }
        Err(join_error) => {
            log::warn!(target: "watchdog", "mDNS re-registration task failed: {}", join_error)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn wildcard_bind_probes_over_loopback() {
        let target = resolve_probe_address("0.0.0.0", 8554).unwrap();
        assert_eq!(target.ip().to_string(), "127.0.0.1");
        assert_eq!(target.port(), 8554);
    }

    #[test]
    fn explicit_bind_probes_its_own_address() {
        let target = resolve_probe_address("192.168.2.7", 8554).unwrap();
        assert_eq!(target.to_string(), "192.168.2.7:8554");
    }

    #[test]
    fn unparsable_bind_disables_the_watchdog() {
        assert!(resolve_probe_address("localhost", 8554).is_none());
    }

    #[tokio::test]
    async fn probe_passes_on_a_live_listener_and_fails_once_it_is_gone() {
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let target = listener.local_addr().unwrap();
        assert!(probe_listener(target, Duration::from_secs(1)).await);

        drop(listener);
        assert!(!probe_listener(target, Duration::from_secs(1)).await);
    }

    #[tokio::test]
    async fn wait_or_cancel_reports_which_side_won() {
        let cancelled = CancellationToken::new();
        cancelled.cancel();
        assert!(wait_or_cancel(Duration::from_secs(60), &cancelled).await);

        let live = CancellationToken::new();
        assert!(!wait_or_cancel(Duration::from_millis(10), &live).await);
    }

    #[tokio::test]
    async fn recovery_confirmation_requires_a_serving_listener() {
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let target = listener.local_addr().unwrap();
        assert!(confirm_recovery(
            target,
            Duration::from_millis(200),
            3,
            Duration::from_millis(10),
            &CancellationToken::new()
        )
        .await);

        drop(listener);
        assert!(!confirm_recovery(
            target,
            Duration::from_millis(50),
            3,
            Duration::from_millis(10),
            &CancellationToken::new()
        )
        .await);
    }

    #[tokio::test]
    async fn watchdog_stays_quiet_while_the_listener_answers() {
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let target = listener.local_addr().unwrap();

        let (rebind_tx, rebind_rx) = watch::channel(0u64);
        let deps = ListenerWatchdogDeps {
            rebind: rebind_tx,
            mdns: Arc::new(Mutex::new(None)),
            mdns_port: 8554,
            mdns_bind: "0.0.0.0".to_string(),
        };
        let cancel = CancellationToken::new();
        let task = tokio::spawn(run(
            Some(ListenerWatchdogConfig {
                probe_target: target,
                probe_interval: Duration::from_millis(10),
                probe_timeout: Duration::from_millis(200),
                failure_threshold: 2,
            }),
            deps,
            cancel.clone(),
        ));
        tokio::time::sleep(Duration::from_millis(80)).await;

        assert_eq!(*rebind_rx.borrow(), 0, "healthy listener must not trigger a rebuild");

        cancel.cancel();
        task.await.unwrap();
    }

    #[tokio::test]
    async fn watchdog_orders_a_rebuild_after_the_listener_dies() {
        use tokio::net::TcpListener;

        let listener = Some(TcpListener::bind("127.0.0.1:0").await.unwrap());
        let target = listener.as_ref().unwrap().local_addr().unwrap();
        // Park the listener in a task so aborting it closes the socket even
        // though this test no longer holds a handle.
        let holder = tokio::spawn(async move {
            let listener = listener.unwrap();
            loop {
                let _ = listener.accept().await;
            }
        });

        let (rebind_tx, mut rebind_rx) = watch::channel(0u64);
        let deps = ListenerWatchdogDeps {
            rebind: rebind_tx,
            mdns: Arc::new(Mutex::new(None)),
            mdns_port: 8554,
            mdns_bind: "0.0.0.0".to_string(),
        };
        let cancel = CancellationToken::new();
        let task = tokio::spawn(run(
            Some(ListenerWatchdogConfig {
                probe_target: target,
                probe_interval: Duration::from_millis(20),
                probe_timeout: Duration::from_millis(200),
                failure_threshold: 2,
            }),
            deps,
            cancel.clone(),
        ));

        holder.abort();
        tokio::time::timeout(Duration::from_secs(5), rebind_rx.changed())
            .await
            .expect("watchdog did not detect the dead listener in time")
            .expect("rebind sender stayed alive");
        assert_eq!(*rebind_rx.borrow(), 1);

        cancel.cancel();
        task.await.unwrap();
    }

    #[tokio::test]
    async fn disabled_watchdog_keeps_the_rebind_sender_alive_until_cancel() {
        let (rebind_tx, rebind_rx) = watch::channel(0u64);
        let deps = ListenerWatchdogDeps {
            rebind: rebind_tx,
            mdns: Arc::new(Mutex::new(None)),
            mdns_port: 8554,
            mdns_bind: "0.0.0.0".to_string(),
        };
        let cancel = CancellationToken::new();
        let task = tokio::spawn(run(None, deps, cancel.clone()));
        tokio::time::sleep(Duration::from_millis(20)).await;

        assert!(
            rebind_rx.has_changed().is_ok(),
            "sender must outlive the server"
        );

        cancel.cancel();
        tokio::time::timeout(Duration::from_secs(1), task)
            .await
            .expect("parked watchdog must exit on cancellation")
            .unwrap();
        assert!(rebind_rx.has_changed().is_err());
    }
}
