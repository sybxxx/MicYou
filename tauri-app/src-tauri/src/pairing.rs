use crate::events::{PairingRequestInfo, SharedEvents};
use crate::secure_channel::UdpSealer;
use serde::Serialize;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use tokio::sync::oneshot;

/// Coordinates first-time device pairing between the network tasks and the
/// frontend. The GUI answers interactively through `resolve_pairing`; headless
/// frontends construct the broker in auto-approve mode and rely on the emitted
/// SAS log line as the audit trail.
pub struct PairingBroker {
    pub auto_approve: bool,
    pending: Mutex<Option<(String, oneshot::Sender<bool>)>>,
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
            pending: Mutex::new(None),
        }
    }

    /// Registers a pairing request and returns a receiver for the verdict.
    /// `None` means the request was already approved automatically.
    pub fn begin(
        &self,
        request_id: &str,
        device_name: &str,
        sas: &str,
        events: &SharedEvents,
    ) -> Option<oneshot::Receiver<bool>> {
        if self.auto_approve {
            log::warn!(
                target: "server",
                "Headless pairing auto-approved for '{device_name}'; verify SAS {sas} was expected",
            );
            return None;
        }
        let (tx, rx) = oneshot::channel();
        let replaced = self
            .pending
            .lock()
            .map(|mut guard| guard.replace((request_id.to_string(), tx)).is_some())
            .unwrap_or(false);
        if replaced {
            log::warn!(target: "server", "Replaced an unanswered pairing request");
        }
        events.pairing_requested(PairingRequestInfo {
            request_id: request_id.to_string(),
            device_name: device_name.to_string(),
            sas: sas.to_string(),
        });
        Some(rx)
    }

    /// Delivers the user's verdict; returns false when no matching request is open.
    pub fn resolve(&self, request_id: &str, accept: bool) -> bool {
        let Ok(mut guard) = self.pending.lock() else {
            return false;
        };
        match guard.as_ref() {
            Some((pending_id, _)) if pending_id == request_id => {
                if let Some((_, tx)) = guard.take() {
                    let _ = tx.send(accept);
                }
                true
            }
            _ => false,
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
