use crate::events::{PairingRequestInfo, SharedEvents};
use crate::secure_channel::UdpSealer;
use serde::Serialize;
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use tokio::sync::oneshot;

/// Coordinates first-time device pairing between the network tasks and the
/// frontend. Phones retry aggressively while a dialog is open, so several
/// requests can be pending at once; verdicts are routed by request id and a
/// dropped connection simply abandons its entry via [`PendingGuard`].
pub struct PairingBroker {
    pub auto_approve: bool,
    pending: Mutex<HashMap<String, oneshot::Sender<bool>>>,
}

impl Default for PairingBroker {
    fn default() -> Self {
        Self::new(false)
    }
}

impl PairingBroker {
    pub fn new(auto_approve: bool) -> Self {
        Self {
            auto_approve,
            pending: Mutex::new(HashMap::new()),
        }
    }

    /// Registers a pairing request and returns a receiver for the verdict plus a
    /// guard that unregisters it when the connection task moves on. `None` means
    /// the request was already approved automatically.
    pub fn begin(
        &self,
        request_id: &str,
        device_name: &str,
        sas: &str,
        events: &SharedEvents,
    ) -> Option<(oneshot::Receiver<bool>, PendingGuard)> {
        if self.auto_approve {
            log::warn!(
                target: "server",
                "Headless pairing auto-approved for '{device_name}'; verify SAS {sas} was expected",
            );
            return None;
        }
        let (tx, rx) = oneshot::channel();
        if self
            .pending
            .lock()
            .map(|mut guard| guard.insert(request_id.to_string(), tx).is_some())
            .unwrap_or(false)
        {
            log::warn!(target: "server", "Replaced pairing request with duplicate id {request_id}");
        }
        events.pairing_requested(PairingRequestInfo {
            request_id: request_id.to_string(),
            device_name: device_name.to_string(),
            sas: sas.to_string(),
        });
        Some((
            rx,
            PendingGuard {
                request_id: request_id.to_string(),
                broker: self,
            },
        ))
    }

    /// Delivers the user's verdict; returns false when no matching request is open.
    pub fn resolve(&self, request_id: &str, accept: bool) -> bool {
        let Ok(mut guard) = self.pending.lock() else {
            return false;
        };
        guard
            .remove(request_id)
            .map(|tx| {
                let _ = tx.send(accept);
                true
            })
            .unwrap_or(false)
    }
}

/// Unregisters its pairing request when dropped, so abandoned connections
/// cannot leave stale entries behind.
pub struct PendingGuard<'a> {
    request_id: String,
    broker: &'a PairingBroker,
}

impl Drop for PendingGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut guard) = self.broker.pending.lock() {
            guard.remove(&self.request_id);
        }
    }
}

/// Crypto material for the one active encrypted audio session. TCP installs it
/// after a completed secure handshake; the UDP receive loop consults it to
/// decrypt datagrams arriving from the bound peer. Tokio's async mutex matches
/// the neighbouring shared-session types.
#[derive(Clone)]
pub struct ActiveSessionCrypto {
    pub peer_ip: IpAddr,
    pub connection_id: u64,
    pub udp: Arc<UdpSealer>,
}

pub type SharedSessionCrypto = Arc<tokio::sync::Mutex<Option<ActiveSessionCrypto>>>;

#[derive(Serialize, Clone, Debug)]
pub struct SessionSecurityState {
    pub encrypted: bool,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::events::{PairingRequestInfo, ServerEvents};

    struct CollectingEvents(Mutex<Vec<PairingRequestInfo>>);
    impl CollectingEvents {
        fn new() -> Self {
            Self(Mutex::new(Vec::new()))
        }
    }
    impl crate::events::ServerEvents for CollectingEvents {
        fn device_connected(&self, _info: crate::tcp_server::DeviceInfo) {}
        fn device_disconnected(&self) {}
        fn audio_metrics(&self, _metrics: crate::stats::AudioMetrics) {}
        fn udp_audio_warning(&self) {}
        fn mute_state_changed(&self, _is_muted: bool) {}
        fn audio_level(&self, _level: u32) {}
        fn audio_spectrum(&self, _raw: Vec<f32>, _processed: Vec<f32>) {}
        fn server_stopped(&self) {}
        fn server_fault(&self, _component: String, _message: String) {}
        fn web_client_count(&self, _count: u32) {}
        fn install_progress(&self, _message: String) {}
        fn aec_status_changed(&self, _status: crate::events::AecStatus) {}
        fn pairing_requested(&self, request: PairingRequestInfo) {
            self.0.lock().unwrap().push(request);
        }
        fn pairing_completed(&self, _device_name: String) {}
        fn session_security_changed(&self, _encrypted: bool) {}
    }

    #[test]
    fn overlapping_pairing_requests_route_verdicts_by_id() {
        let broker = PairingBroker::new(false);
        let events = Arc::new(CollectingEvents::new());

        let (rx1, guard1) = broker
            .begin("conn-1", "Phone A", "111111", &(events.clone() as Arc<dyn ServerEvents>))
            .unwrap();
        let (rx2, guard2) = broker
            .begin("conn-2", "Phone B", "222222", &(events as Arc<dyn ServerEvents>))
            .unwrap();

        // Approving the second attempt must not be swallowed by the first.
        assert!(broker.resolve("conn-2", true));
        assert_eq!(rx2.await_now(true), true);

        // The abandoned second entry is cleaned up when its guard drops, and a
        // stale verdict for it is a harmless no-op afterwards.
        drop(guard2);
        assert!(!broker.resolve("conn-2", false));

        // The first attempt is still independently answerable.
        assert!(broker.resolve("conn-1", true));
        assert_eq!(rx1.await_now(true), true);
        drop(guard1);
        assert!(!broker.resolve("conn-1", true));
    }

    trait AwaitNow {
        fn await_now(self, expected: bool) -> bool;
    }
    impl AwaitNow for tokio::sync::oneshot::Receiver<bool> {
        fn await_now(mut self, expected: bool) -> bool {
            match self.try_recv() {
                Ok(v) => v == expected,
                Err(_) => panic!("verdict not delivered"),
            }
        }
    }
}
