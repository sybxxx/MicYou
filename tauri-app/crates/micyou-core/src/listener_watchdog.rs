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

use crate::discovery::NetworkManager;

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
#[derive(Clone, Debug)]
pub struct ListenerWatchdogConfig {
    pub bind_address: String,
    pub port: u16,
    pub probe_interval: Duration,
    pub probe_timeout: Duration,
    pub failure_threshold: u32,
}

impl ListenerWatchdogConfig {
    pub fn for_bind(bind_address: &str, port: u16) -> Option<Self> {
        resolve_probe_address(bind_address, port)?;
        Some(Self {
            bind_address: bind_address.to_string(),
            port,
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

/// Resolve the loopback probe address used for an explicit bind or wildcard bind.
/// Wildcard binds additionally probe the current LAN address in [`probe_targets`].
pub fn resolve_probe_address(bind_address: &str, port: u16) -> Option<SocketAddr> {
    let ip: IpAddr = if bind_address.trim() == "0.0.0.0" || bind_address.trim().is_empty() {
        IpAddr::from([127, 0, 0, 1])
    } else if crate::transport::net_bind::is_unspecified_v6(bind_address) {
        "::1".parse().ok()?
    } else {
        crate::transport::net_bind::strip_brackets(bind_address.trim())
            .parse()
            .ok()?
    };
    Some(SocketAddr::new(ip, port))
}

fn is_wildcard_bind(bind_address: &str) -> bool {
    bind_address.trim().is_empty()
        || bind_address.trim() == "0.0.0.0"
        || crate::transport::net_bind::is_unspecified_v6(bind_address)
}

fn should_refresh_mdns(
    current_lan_ip: Option<IpAddr>,
    last_advertised_ip: Option<IpAddr>,
    network_was_unavailable: bool,
) -> bool {
    current_lan_ip.is_some() && (network_was_unavailable || current_lan_ip != last_advertised_ip)
}

async fn current_lan_ip(config: &ListenerWatchdogConfig) -> Option<IpAddr> {
    if !is_wildcard_bind(&config.bind_address) {
        return None;
    }
    tokio::task::spawn_blocking(NetworkManager::current_best_ip)
        .await
        .ok()
        .flatten()
}

fn probe_targets_for(bind_address: &str, port: u16, lan_ip: Option<IpAddr>) -> Vec<SocketAddr> {
    let primary = resolve_probe_address(bind_address, port)
        .expect("watchdog config must contain a valid bind address");
    let mut targets = vec![primary];
    if is_wildcard_bind(bind_address) {
        if let Some(ip) = lan_ip {
            let lan_target = SocketAddr::new(ip, port);
            if lan_target != primary {
                targets.push(lan_target);
            }
        }
    }
    targets
}

async fn probe_targets(config: &ListenerWatchdogConfig) -> (Vec<SocketAddr>, Option<IpAddr>) {
    let lan_ip = current_lan_ip(config).await;
    let targets = probe_targets_for(&config.bind_address, config.port, lan_ip);
    (targets, lan_ip)
}

async fn probe_all(targets: &[SocketAddr], timeout_duration: Duration) -> bool {
    for target in targets {
        if !probe_listener(*target, timeout_duration).await {
            return false;
        }
    }
    true
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
    config: &ListenerWatchdogConfig,
    probe_timeout: Duration,
    attempts: u32,
    retry_interval: Duration,
    cancel_token: &CancellationToken,
) -> bool {
    for _ in 0..attempts {
        if cancel_token.is_cancelled() {
            return false;
        }
        let (targets, _) = probe_targets(config).await;
        if probe_all(&targets, probe_timeout).await {
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
    // Keep the last successfully advertised address. If the adapter is briefly
    // unavailable, retain the old value so a later address change still
    // triggers mDNS refresh.
    let mut last_advertised_ip = current_lan_ip(&config).await;
    let mut network_was_unavailable = last_advertised_ip.is_none();
    // The first tick fires immediately, validating the freshly bound listeners
    // right after startup; later ticks follow the configured interval.
    let mut probe_tick = tokio::time::interval(config.probe_interval);
    probe_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => break,
            _ = probe_tick.tick() => {
                let (targets, lan_ip) = probe_targets(&config).await;
                let should_refresh = should_refresh_mdns(
                    lan_ip,
                    last_advertised_ip,
                    network_was_unavailable,
                );
                network_was_unavailable |= lan_ip.is_none();
                if probe_all(&targets, config.probe_timeout).await {
                    consecutive_failures = 0;
                    if should_refresh {
                        if let Some(ip) = lan_ip {
                            log::info!(
                                target: "watchdog",
                                "local LAN address changed to {}; refreshing mDNS",
                                ip
                            );
                        }
                        if reregister_mdns(&deps).await {
                            last_advertised_ip = lan_ip;
                            network_was_unavailable = false;
                        }
                    }
                    continue;
                }
                consecutive_failures += 1;
                if consecutive_failures < config.failure_threshold {
                    log::debug!(
                        target: "watchdog",
                        "local listener probe failed for {:?} ({}/{})",
                        targets,
                        consecutive_failures,
                        config.failure_threshold
                    );
                    continue;
                }
                consecutive_failures = 0;
                log::warn!(
                    target: "watchdog",
                    "control listener probes {:?} failed; rebuilding network listeners",
                    targets
                );
                let generation = *deps.rebind.borrow();
                deps.rebind.send_replace(generation.wrapping_add(1));
                // Refresh mDNS only once the rebuilt listeners actually answer
                // again, so a persistently dead bind cannot churn the daemon on
                // every watchdog cycle.
                if confirm_recovery(
                    &config,
                    config.probe_timeout,
                    RECOVERY_CONFIRM_ATTEMPTS,
                    REBIND_RETRY_INTERVAL,
                    &cancel_token,
                )
                .await
                {
                    if reregister_mdns(&deps).await {
                        if let Some(ip) = current_lan_ip(&config).await {
                            last_advertised_ip = Some(ip);
                        }
                        network_was_unavailable = false;
                    }
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
async fn reregister_mdns(deps: &ListenerWatchdogDeps) -> bool {
    let mut mdns = deps.mdns.lock().await;
    // A failed refresh leaves the slot empty. Still try to register on the next
    // watchdog tick so a temporary mDNS/adapter failure does not permanently
    // disable discovery until the user restarts the desktop app.
    if let Some(previous) = mdns.take() {
        previous.stop_mdns();
    }
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
            true
        }
        Ok(Err(error)) => {
            log::warn!(target: "watchdog", "mDNS re-registration failed: {}", error);
            false
        }
        Err(join_error) => {
            log::warn!(target: "watchdog", "mDNS re-registration task failed: {}", join_error);
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn wildcard_bind_always_probes_loopback() {
        let targets = probe_targets_for("0.0.0.0", 8554, None);
        assert_eq!(targets, vec!["127.0.0.1:8554".parse().unwrap()]);
    }

    #[test]
    fn wildcard_bind_also_probes_the_current_lan_address() {
        let targets = probe_targets_for("0.0.0.0", 8554, Some("192.168.2.8".parse().unwrap()));
        assert_eq!(
            targets,
            vec![
                "127.0.0.1:8554".parse().unwrap(),
                "192.168.2.8:8554".parse().unwrap()
            ]
        );
    }

    #[test]
    fn mdns_refreshes_after_network_returns_with_the_same_address() {
        let ip = Some("192.168.2.8".parse().unwrap());
        assert!(should_refresh_mdns(ip, ip, true));
        assert!(!should_refresh_mdns(ip, ip, false));
        assert!(should_refresh_mdns(
            Some("192.168.2.9".parse().unwrap()),
            ip,
            false
        ));
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
        let config = ListenerWatchdogConfig {
            bind_address: "127.0.0.1".to_string(),
            port: target.port(),
            probe_interval: Duration::from_millis(10),
            probe_timeout: Duration::from_millis(200),
            failure_threshold: 2,
        };
        assert!(
            confirm_recovery(
                &config,
                Duration::from_millis(200),
                3,
                Duration::from_millis(10),
                &CancellationToken::new()
            )
            .await
        );

        drop(listener);
        assert!(
            !confirm_recovery(
                &config,
                Duration::from_millis(50),
                3,
                Duration::from_millis(10),
                &CancellationToken::new()
            )
            .await
        );
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
                bind_address: "127.0.0.1".to_string(),
                port: target.port(),
                probe_interval: Duration::from_millis(10),
                probe_timeout: Duration::from_millis(200),
                failure_threshold: 2,
            }),
            deps,
            cancel.clone(),
        ));
        tokio::time::sleep(Duration::from_millis(80)).await;

        assert_eq!(
            *rebind_rx.borrow(),
            0,
            "healthy listener must not trigger a rebuild"
        );

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
                bind_address: "127.0.0.1".to_string(),
                port: target.port(),
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
