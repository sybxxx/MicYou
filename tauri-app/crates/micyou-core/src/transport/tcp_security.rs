//! Encrypted-handshake and framed control transport.
use super::*;
use micyou_protocol::micyou::SecureResult;

pub(super) async fn read_raw_frame<R>(
    reader: &mut R,
    cancel_token: &CancellationToken,
) -> Result<([u8; FRAME_HEADER_LEN], Vec<u8>), Box<dyn Error + Send + Sync>>
where
    R: AsyncReadExt + Unpin,
{
    tokio::select! {
        _ = cancel_token.cancelled() => Err(IoError::new(ErrorKind::Other, "cancelled").into()),
        result = timeout(FRAME_READ_TIMEOUT, async {
            let mut header = [0u8; FRAME_HEADER_LEN];
            reader.read_exact(&mut header).await?;
            let payload_len = parse_frame_header(&header)?;
            // The parser guarantees this allocation never exceeds header + 1 MiB.
            let mut payload = vec![0u8; payload_len];
            reader.read_exact(&mut payload).await?;
            Ok::<Vec<u8>, IoError>(payload)
        }) => {
            let payload = result.map_err(|_| IoError::new(ErrorKind::TimedOut, "control frame read timed out"))??;
            Ok((header_from_payload(payload.len()), payload))
        }
    }
}

pub(super) fn header_from_payload(payload_len: usize) -> [u8; FRAME_HEADER_LEN] {
    let mut header = [0u8; FRAME_HEADER_LEN];
    header[..4].copy_from_slice(&PACKET_MAGIC.to_be_bytes());
    header[4..].copy_from_slice(&(payload_len as i32).to_be_bytes());
    header
}

/// Decrypts a frame payload when the session is encrypted, then decodes it.
pub(super) fn decode_frame(
    session_crypto: &Option<Arc<SessionCrypto>>,
    header8: &[u8; FRAME_HEADER_LEN],
    payload: &[u8],
) -> Result<MessageWrapper, Box<dyn Error + Send + Sync>> {
    let plaintext = match session_crypto {
        Some(crypto) => crypto.open_tcp_payload(Direction::ClientToServer, header8, payload)?,
        None => payload.to_vec(),
    };
    Ok(MessageWrapper::decode(plaintext.as_slice())?)
}

#[allow(clippy::too_many_arguments)]
pub(super) async fn establish_secure_session(
    socket: &mut TcpStream,
    security: &crate::secure_channel::ServerSecurity,
    broker: &PairingBroker,
    events: &SharedEvents,
    hello_payload: &[u8],
    connection_id: u64,
    cancel_token: &CancellationToken,
) -> Result<Arc<SessionCrypto>, Box<dyn Error + Send + Sync>> {
    let (server_hello, pending, sas, client_known, claimed_name) =
        security.handshake.accept_client_hello(hello_payload)?;

    let hello_frame = MessageWrapper {
        secure_server_hello: Some(server_hello),
        ..MessageWrapper::default()
    };
    write_plain_frame(socket, &hello_frame.encode_to_vec(), cancel_token).await?;

    if !client_known {
        let request_id = format!("conn-{connection_id}");
        let verdict = match broker.begin(&request_id, &claimed_name, &sas, events) {
            Some((rx, guard)) => {
                let answer = tokio::select! {
                    _ = cancel_token.cancelled() => false,
                    result = timeout(PAIRING_TIMEOUT, rx) => matches!(result, Ok(Ok(true))),
                };
                drop(guard);
                answer
            }
            None => true,
        };
        if !verdict {
            log::warn!(
                target: "server",
                "Pairing for '{claimed_name}' denied or timed out",
            );
            return Err("pairing rejected".into());
        }
        let identity_b64 = pending.client_identity_b64().to_string();
        security
            .handshake
            .remember_client(&identity_b64, &claimed_name)?;
        events.pairing_completed(claimed_name);
    }

    // The verdict travels inside the encrypted channel: both sides derive the
    // session keys right after the signed exchange, so the client authenticates
    // acceptance instead of trusting a plaintext flag. The client's encrypted
    // SecureConfirm is consumed here so the next frame on the wire is Connect.
    let session = Arc::new(pending.finish()?);
    let (confirm_header, confirm_payload) = read_raw_frame(socket, cancel_token).await?;
    let confirmed = decode_frame(
        &Some(Arc::clone(&session)),
        &confirm_header,
        &confirm_payload,
    )?;
    if confirmed.secure_confirm.is_none() {
        return Err("expected SecureConfirm after key exchange".into());
    }

    let result_frame = MessageWrapper {
        secure_result: Some(SecureResult { accepted: true }),
        ..MessageWrapper::default()
    };
    let plain = result_frame.encode_to_vec();
    let header = header_from_payload(plain.len() + AEAD_TAG_LEN);
    let sealed = session.seal_tcp_payload(Direction::ServerToClient, &header, &plain)?;
    tokio::select! {
        _ = cancel_token.cancelled() => return Err(IoError::other("cancelled").into()),
        result = timeout(FRAME_WRITE_TIMEOUT, async {
            socket.write_all(&header).await?;
            socket.write_all(&sealed).await?;
            socket.flush().await
        }) => result.map_err(|_| IoError::new(ErrorKind::TimedOut, "secure result write timed out"))??,
    }
    Ok(session)
}

pub(super) async fn write_plain_frame(
    socket: &mut TcpStream,
    payload: &[u8],
    cancel_token: &CancellationToken,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    let header = header_from_payload(payload.len());
    tokio::select! {
        _ = cancel_token.cancelled() => Err(IoError::new(ErrorKind::Other, "cancelled").into()),
        result = timeout(FRAME_WRITE_TIMEOUT, async {
            socket.write_all(&header).await?;
            socket.write_all(payload).await?;
            socket.flush().await
        }) => {
            result.map_err(|_| IoError::new(ErrorKind::TimedOut, "frame write timed out"))??;
            Ok(())
        }
    }
}
