//! Regression tests over owned loopback sockets, without a real audio device.
use super::*;
use crate::events::{AecStatus, DownloadProgress, ServerEvents, SpectrumPayload};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use micyou_protocol::SECURE_SUITE_V1;

#[derive(Default)]
struct TestEvents {
    connected: std::sync::atomic::AtomicUsize,
}
impl ServerEvents for TestEvents {
    fn device_connected(&self, _: DeviceInfo) {
        self.connected.fetch_add(1, Ordering::Relaxed);
    }
    fn device_disconnected(&self) {}
    fn audio_metrics(&self, _: crate::stats::AudioMetrics) {}
    fn udp_audio_warning(&self) {}
    fn mute_state_changed(&self, _: bool) {}
    fn audio_level(&self, _: u32) {}
    fn audio_spectrum(&self, _: SpectrumPayload) {}
    fn server_stopped(&self) {}
    fn web_client_count(&self, _: u32) {}
    fn install_progress(&self, _: String) {}
    fn aec_status_changed(&self, _: AecStatus) {}
    fn monitoring_state_changed(&self, _: bool) {}
    fn plugin_download_progress(&self, _: DownloadProgress) {}
}
struct TestHost;
impl crate::host::HostIntegration for TestHost {
    fn open_url(&self, _: &str) -> Result<(), String> {
        Err("test host".into())
    }
    fn notify(&self, _: &str, _: &str) -> Result<(), String> {
        Ok(())
    }
    fn register_hotkey(&self, _: &str, _: crate::host::HotkeyCallback) -> Result<(), String> {
        Ok(())
    }
    fn open_plugin_panel(&self, _: &str, _: &str, _: &str) -> Result<(), String> {
        Err("test host".into())
    }
}
struct TestDir(std::path::PathBuf);
impl TestDir {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "micyou-core-secure-test-{}-{}-{}",
            std::process::id(),
            crate::stats::unix_millis(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for TestDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

async fn secure_client_connect(
    socket: &mut TcpStream,
    store_dir: &std::path::Path,
) -> (Arc<SessionCrypto>, String) {
    use crate::secure_channel::{
        client_hello_core, derive_session_keys, server_hello_core, signed_transcript,
        transcript_digest, SessionKeys,
    };

    let identity = crate::security::ServerIdentity::generate().unwrap();
    let rng = ring::rand::SystemRandom::new();
    let eph_private =
        ring::agreement::EphemeralPrivateKey::generate(&ring::agreement::X25519, &rng).unwrap();
    let eph_public = eph_private.compute_public_key().unwrap();

    let mut hello = micyou_protocol::micyou::SecureClientHello {
        suite_mask: 1 << (SECURE_SUITE_V1 - 1),
        ephemeral_pub_key: eph_public.as_ref().to_vec(),
        identity_pub_key: identity.public_key().to_vec(),
        transcript_signature: Vec::new(),
        device_name: "TestPhone".to_string(),
    };
    let ch_core = client_hello_core(&hello);
    hello.transcript_signature = identity.sign(&signed_transcript(&[&ch_core]));

    // Magic exchange.
    socket.write_all(HANDSHAKE_CLIENT_STR).await.unwrap();
    let mut server_magic = vec![0u8; HANDSHAKE_SERVER_STR.len()];
    socket.read_exact(&mut server_magic).await.unwrap();

    // Client hello frame (plaintext).
    let hello_wrapper = MessageWrapper {
        secure_client_hello: Some(hello.clone()),
        ..Default::default()
    };
    let body = hello_wrapper.encode_to_vec();
    socket
        .write_all(&(PACKET_MAGIC.to_be_bytes()))
        .await
        .unwrap();
    socket
        .write_all(&(body.len() as i32).to_be_bytes())
        .await
        .unwrap();
    socket.write_all(&body).await.unwrap();

    // Server hello (plaintext).
    let mut header = [0u8; FRAME_HEADER_LEN];
    socket.read_exact(&mut header).await.unwrap();
    let payload_len = parse_frame_header(&header).unwrap();
    let mut payload = vec![0u8; payload_len];
    socket.read_exact(&mut payload).await.unwrap();
    let server_hello = MessageWrapper::decode(payload.as_slice())
        .unwrap()
        .secure_server_hello
        .expect("server hello must arrive");

    assert_eq!(server_hello.suite, SECURE_SUITE_V1);
    let sh_core = server_hello_core(
        server_hello.suite,
        &server_hello.identity_pub_key,
        &server_hello.ephemeral_pub_key,
    );
    ring::signature::UnparsedPublicKey::new(
        &ring::signature::ED25519,
        server_hello.identity_pub_key.as_slice(),
    )
    .verify(
        &signed_transcript(&[&ch_core, &sh_core]),
        &server_hello.transcript_signature,
    )
    .expect("server signature must verify");

    let peer_public = ring::agreement::UnparsedPublicKey::new(
        &ring::agreement::X25519,
        server_hello.ephemeral_pub_key.clone(),
    );
    let shared: [u8; 32] = ring::agreement::agree_ephemeral(eph_private, &peer_public, |s| {
        let mut out = [0u8; 32];
        out.copy_from_slice(s);
        out
    })
    .unwrap();
    let transcript = transcript_digest(&ch_core, &sh_core);
    let (keys, _sas) = derive_session_keys(&transcript, &shared).unwrap();
    let session = SessionCrypto::new(&SessionKeys {
        tcp_key_c2s: keys.tcp_key_c2s,
        tcp_key_s2c: keys.tcp_key_s2c,
        udp_key_c2s: keys.udp_key_c2s,
        udp_nonce_prefix: keys.udp_nonce_prefix,
    })
    .unwrap();

    // SecureConfirm (encrypted, c2s seq 0).
    let confirm = MessageWrapper {
        secure_confirm: Some(micyou_protocol::micyou::SecureConfirm {
            device_name: "TestPhone".to_string(),
        }),
        ..Default::default()
    };
    let plain = confirm.encode_to_vec();
    let sealed_header = header_from_payload(plain.len() + AEAD_TAG_LEN);
    let sealed = session
        .seal_tcp_payload(Direction::ClientToServer, &sealed_header, &plain)
        .unwrap();
    socket.write_all(&sealed_header).await.unwrap();
    socket.write_all(&sealed).await.unwrap();

    // SecureResult (encrypted, s2c seq 0).
    let mut header = [0u8; FRAME_HEADER_LEN];
    socket.read_exact(&mut header).await.unwrap();
    let payload_len = parse_frame_header(&header).unwrap();
    let mut payload = vec![0u8; payload_len];
    socket.read_exact(&mut payload).await.unwrap();
    let opened = session
        .open_tcp_payload(Direction::ServerToClient, &header, &payload)
        .unwrap();
    let result = MessageWrapper::decode(opened.as_slice())
        .unwrap()
        .secure_result
        .expect("secure result must arrive");
    assert!(result.accepted);

    // The auto-approve broker path must have persisted the device.
    let paired =
        crate::security::PairedDeviceStore::load_or_create(store_dir.join("paired_devices.json"))
            .unwrap();
    assert!(paired
        .find(&BASE64.encode(&hello.identity_pub_key))
        .is_some());

    (Arc::new(session), "TestPhone".to_string())
}

async fn send_encrypted(client: &mut TcpStream, crypto: &SessionCrypto, message: MessageWrapper) {
    let body = message.encode_to_vec();
    let header = header_from_payload(body.len() + AEAD_TAG_LEN);
    let sealed = crypto
        .seal_tcp_payload(Direction::ClientToServer, &header, &body)
        .unwrap();
    client.write_all(&header).await.unwrap();
    client.write_all(&sealed).await.unwrap();
}

fn serve_test_socket(
    socket: TcpStream,
    peer: SocketAddr,
    state: &crate::server::ServerState,
    audio_tx: tokio::sync::mpsc::Sender<AudioStreamEvent>,
    security: crate::secure_channel::SharedSecurity,
    token: CancellationToken,
) -> JoinHandle<Result<(), Box<dyn Error + Send + Sync>>> {
    tokio::spawn(handle_client(
        socket,
        peer,
        state.events.clone(),
        audio_tx,
        state.network_stats.clone(),
        ConnectionMode::Wifi,
        state.active_connection.clone(),
        state.takeover_lock.clone(),
        state.active_audio_session.clone(),
        state.plugins.clone(),
        security,
        state.session_crypto.clone(),
        Arc::new(PairingBroker::new(true)),
        token,
    ))
}

async fn send_test_connect(client: &mut TcpStream, crypto: &SessionCrypto, session_id: i64) {
    send_encrypted(
        client,
        crypto,
        MessageWrapper {
            connect: Some(micyou_protocol::micyou::ConnectMessage {
                session_id,
                platform: "android".into(),
                transport: "tcp".into(),
            }),
            ..Default::default()
        },
    )
    .await;
}

async fn receive_starting(rx: &mut tokio::sync::mpsc::Receiver<AudioStreamEvent>) -> u64 {
    match timeout(Duration::from_secs(2), rx.recv())
        .await
        .unwrap()
        .unwrap()
    {
        AudioStreamEvent::SessionStarting {
            epoch,
            info: Some(info),
            ..
        } => {
            assert!(info.encrypted);
            assert_eq!(info.device.platform, ClientPlatform::Android);
            epoch
        }
        _ => panic!("expected secure session event"),
    }
}

fn opus_packet(session_id: i64) -> micyou_protocol::micyou::AudioPacketMessageOrdered {
    micyou_protocol::micyou::AudioPacketMessageOrdered {
        sequence_number: 0,
        session_id,
        audio_packet: Some(micyou_protocol::micyou::AudioPacketMessage {
            buffer: vec![0xf8, 0xff, 0xfe],
            sample_rate: 48_000,
            channel_count: 1,
            audio_format: 2,
            codec: micyou_protocol::CODEC_OPUS,
        }),
        fec_sequence_number: -1,
        ..Default::default()
    }
}

async fn send_and_receive_opus(
    client: &mut TcpStream,
    crypto: &SessionCrypto,
    rx: &mut tokio::sync::mpsc::Receiver<AudioStreamEvent>,
    session_id: i64,
    epoch: u64,
) {
    send_encrypted(
        client,
        crypto,
        MessageWrapper {
            audio_packet: Some(opus_packet(session_id)),
            ..Default::default()
        },
    )
    .await;
    match timeout(Duration::from_secs(2), rx.recv())
        .await
        .unwrap()
        .unwrap()
    {
        AudioStreamEvent::Packet {
            packet,
            epoch: actual,
        } => {
            assert_eq!(actual, epoch);
            assert_eq!(packet.session_id, session_id);
            assert_eq!(
                packet.audio_packet.unwrap().codec,
                micyou_protocol::CODEC_OPUS
            );
        }
        _ => panic!("expected encrypted Opus packet"),
    }
}

#[tokio::test]
async fn encrypted_tcp_preserves_opus_and_does_not_claim_connected_on_handshake() {
    let temp = TestDir::new();
    let events = Arc::new(TestEvents::default());
    let state = crate::server::ServerState::new(events.clone(), Arc::new(TestHost), None);
    let identity = Arc::new(crate::security::ServerIdentity::generate().unwrap());
    let store =
        crate::security::PairedDeviceStore::load_or_create(temp.0.join("paired_devices.json"))
            .unwrap();
    let security = Arc::new(crate::secure_channel::ServerSecurity {
        handshake: crate::secure_channel::SecureHandshake::with_paired_devices(
            identity,
            Arc::new(std::sync::Mutex::new(store)),
        ),
        require_encryption: true,
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let (audio_tx, mut audio_rx) = tokio::sync::mpsc::channel(8);
    let token = CancellationToken::new();

    let mut client = TcpStream::connect(address).await.unwrap();
    let (socket, peer) = listener.accept().await.unwrap();
    let server = serve_test_socket(
        socket,
        peer,
        &state,
        audio_tx.clone(),
        security.clone(),
        token.clone(),
    );
    let (crypto, _) = timeout(
        Duration::from_secs(3),
        secure_client_connect(&mut client, &temp.0),
    )
    .await
    .unwrap();
    send_test_connect(&mut client, &crypto, 991).await;
    let epoch = receive_starting(&mut audio_rx).await;
    assert_eq!(events.connected.load(Ordering::Relaxed), 0);
    assert!(!state.streaming_status().await.is_connected);
    send_and_receive_opus(&mut client, &crypto, &mut audio_rx, 991, epoch).await;

    // Publish a second secure session while the first reader is still alive.
    // Waiting for the old handler's cleanup makes the race observable rather
    // than checking only the handshake's happy path.
    let mut next_client = TcpStream::connect(address).await.unwrap();
    let (next_socket, next_peer) = listener.accept().await.unwrap();
    let next_server = serve_test_socket(
        next_socket,
        next_peer,
        &state,
        audio_tx,
        security,
        token.clone(),
    );
    let (next_crypto, _) = timeout(
        Duration::from_secs(3),
        secure_client_connect(&mut next_client, &temp.0),
    )
    .await
    .unwrap();
    send_test_connect(&mut next_client, &next_crypto, 992).await;
    let next_epoch = receive_starting(&mut audio_rx).await;
    assert_ne!(next_epoch, epoch);
    let _old_result = timeout(Duration::from_secs(2), server)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        state
            .active_connection
            .lock()
            .await
            .as_ref()
            .unwrap()
            .connection_id,
        next_epoch
    );
    assert_eq!(
        state
            .session_crypto
            .lock()
            .await
            .as_ref()
            .unwrap()
            .connection_id,
        next_epoch
    );
    assert!(
        matches!(*state.active_audio_session.read().unwrap(), ActiveAudioSession::Bound { session_id: 992, epoch, .. } if epoch == next_epoch)
    );
    assert_ne!(state.network_stats.get_tcp_connected_time(), 0);
    send_and_receive_opus(
        &mut next_client,
        &next_crypto,
        &mut audio_rx,
        992,
        next_epoch,
    )
    .await;
    assert_eq!(events.connected.load(Ordering::Relaxed), 0);

    token.cancel();
    drop(client);
    drop(next_client);
    let _result = timeout(Duration::from_secs(2), next_server)
        .await
        .unwrap()
        .unwrap();
    assert!(state.active_connection.lock().await.is_none());
    assert!(state.session_crypto.lock().await.is_none());
    assert!(matches!(
        *state.active_audio_session.read().unwrap(),
        ActiveAudioSession::Inactive
    ));
    state.audio_output.shutdown();
}

#[test]
fn pairing_payload_retains_camel_case_keys() {
    let value = serde_json::to_value(crate::events::PairingRequestInfo {
        request_id: "conn-1".into(),
        device_name: "Phone".into(),
        sas: "123456".into(),
    })
    .unwrap();
    assert_eq!(value["requestId"], "conn-1");
    assert_eq!(value["deviceName"], "Phone");
}

#[tokio::test]
async fn pairing_verdicts_are_routed_by_attempt_and_cancelled_attempts_are_removed() {
    let events: SharedEvents = Arc::new(TestEvents::default());
    let broker = PairingBroker::new(false);
    let (mut first, first_guard) = broker.begin("first", "Phone", "123456", &events).unwrap();
    let (mut second, second_guard) = broker.begin("second", "Phone", "654321", &events).unwrap();
    assert!(broker.resolve("second", true));
    assert!(second.try_recv().unwrap());
    drop(first_guard);
    assert!(!broker.resolve("first", true));
    assert!(matches!(
        first.try_recv(),
        Err(tokio::sync::oneshot::error::TryRecvError::Closed)
    ));
    drop(second_guard);
    assert!(!broker.resolve("second", true));
}
