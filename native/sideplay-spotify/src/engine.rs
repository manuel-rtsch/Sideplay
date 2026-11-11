//! One process-wide librespot session and player, guarded independently of the JNI callers.

use crate::audio::{PcmSink, CHANNELS, SAMPLE_RATE};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::SessionConfig;
use librespot_core::session::Session;
use librespot_core::SpotifyUri;
use librespot_playback::config::{Bitrate, PlayerConfig};
use librespot_playback::mixer::VolumeGetter;
use librespot_playback::player::{Player, PlayerEventChannel};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

struct Volume(AtomicU16);

struct SharedVolume(Arc<Volume>);

impl VolumeGetter for SharedVolume {
    fn attenuation_factor(&self) -> f64 {
        let volume = self.0 .0.load(Ordering::Relaxed).min(65_535) as f64 / 65_535.0;
        volume * volume
    }
}

struct State {
    session: Option<Session>,
    access_token: Option<String>,
    player: Option<Arc<Player>>,
    events: Option<PlayerEventChannel>,
    volume: Arc<Volume>,
}

impl Default for State {
    fn default() -> Self {
        Self {
            session: None,
            access_token: None,
            player: None,
            events: None,
            volume: Arc::new(Volume(AtomicU16::new(65_535))),
        }
    }
}

/// Upper bound for one librespot access-point connection attempt.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(20);

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static STATE: OnceLock<Mutex<State>> = OnceLock::new();
static AUDIO_RX: OnceLock<Mutex<Receiver<Vec<u8>>>> = OnceLock::new();

pub(crate) fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .worker_threads(2)
            .build()
            .expect("tokio runtime")
    })
}

fn state() -> &'static Mutex<State> {
    STATE.get_or_init(|| Mutex::new(State::default()))
}

fn audio_rx() -> &'static Mutex<Receiver<Vec<u8>>> {
    AUDIO_RX.get_or_init(|| {
        let (tx, rx) = sync_channel::<Vec<u8>>(64);
        AUDIO_TX.set(tx).ok();
        Mutex::new(rx)
    })
}

static AUDIO_TX: OnceLock<SyncSender<Vec<u8>>> = OnceLock::new();
static TMP_DIR: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();
static CACHE: OnceLock<Mutex<Option<(PathBuf, u64)>>> = OnceLock::new();

pub fn set_tmp_dir(path: &str) {
    let slot = TMP_DIR.get_or_init(|| Mutex::new(None));
    if let Ok(mut guard) = slot.lock() {
        *guard = Some(PathBuf::from(path));
    }
}

/// Enables librespot's on-disk audio cache. The cache stores the encrypted CDN blobs and is
/// bounded by `size_limit` bytes; librespot evicts least-recently-used entries past the limit.
pub fn set_cache_dir(path: &str, size_limit: u64) {
    let slot = CACHE.get_or_init(|| Mutex::new(None));
    if let Ok(mut guard) = slot.lock() {
        *guard = Some((PathBuf::from(path), size_limit));
    }
}

fn cache() -> Option<Cache> {
    let (path, size_limit) = CACHE
        .get()
        .and_then(|slot| slot.lock().ok().and_then(|guard| guard.clone()))?;
    match Cache::new::<PathBuf>(None, None, Some(path), Some(size_limit)) {
        Ok(cache) => Some(cache),
        Err(error) => {
            log::warn!("audio cache unavailable: {error}");
            None
        }
    }
}

fn tmp_dir() -> PathBuf {
    TMP_DIR
        .get()
        .and_then(|slot| slot.lock().ok().and_then(|guard| guard.clone()))
        .unwrap_or_else(std::env::temp_dir)
}

pub fn init() -> Result<(), String> {
    // Force the audio channel (and its sender) to exist before the player starts.
    let _ = audio_rx();
    Ok(())
}

pub(crate) fn run<F: std::future::Future>(future: F) -> F::Output {
    runtime().block_on(future)
}

pub(crate) fn with_session_and_token<T>(
    f: impl FnOnce(&Session, &str) -> Result<T, String>,
) -> Result<T, String> {
    // Clone out of the lock: catalog calls may make several network round trips, and the audio
    // event poller must never wait behind them.
    let (session, token) = {
        let guard = state().lock().map_err(|_| "STATE_LOCK".to_string())?;
        let session = guard
            .session
            .clone()
            .ok_or_else(|| "SESSION_NOT_CONNECTED".to_string())?;
        let token = guard
            .access_token
            .clone()
            .ok_or_else(|| "SESSION_NOT_CONNECTED".to_string())?;
        (session, token)
    };
    f(&session, &token)
}

pub fn connect(access_token: &str) -> Result<(), String> {
    disconnect();
    let rt = runtime();
    let _guard = rt.enter();
    // The OAuth token comes from the desktop/keymaster client. librespot defaults to the Android
    // client id on this target, which would make Login5 reject the stored credential.
    let config = SessionConfig {
        client_id: "65b708073fc0480ea92a077233ca87bd".to_string(),
        tmp_dir: tmp_dir(),
        ..Default::default()
    };
    let session = Session::new(config, cache());
    // A fresh TCP handshake has no deadline of its own, and `Session::connect` may walk up to six
    // access points. Bound it so a bad network cannot wedge the caller (and the UI waiting on it)
    // indefinitely; the caller retries with a new session on the next play.
    let connect = session.connect(Credentials::with_access_token(access_token), true);
    match rt.block_on(tokio::time::timeout(CONNECT_TIMEOUT, connect)) {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(format!("SESSION_CONNECT_FAILED: {e}")),
        Err(_) => return Err("SESSION_CONNECT_TIMEOUT".to_string()),
    }

    let mut guard = state().lock().map_err(|_| "STATE_LOCK".to_string())?;
    guard.session = Some(session);
    guard.access_token = Some(access_token.to_string());
    Ok(())
}

pub fn set_access_token(access_token: &str) -> Result<(), String> {
    let mut guard = state().lock().map_err(|_| "STATE_LOCK".to_string())?;
    let Some(session) = guard.session.as_ref() else {
        return Err("SESSION_NOT_CONNECTED".to_string());
    };
    // librespot marks the session invalid when the access point connection closes or the
    // keep-alive times out. The stored `Session` handle stays usable, so without this check a
    // dead session would accept token updates forever and every later track would load with no
    // audio key. Reporting the invalid state lets the Kotlin side reconnect from scratch.
    if session.is_invalid() {
        return Err("SESSION_INVALID".to_string());
    }
    guard.access_token = Some(access_token.to_string());
    Ok(())
}

pub fn disconnect() {
    // Take the old handles out and release `STATE` before touching them. `Session::shutdown`
    // waits on librespot's component mutexes; when the access-point connection has stalled, a
    // network task can be holding one of them, so the call may not return. Holding `STATE`
    // across it would wedge every native call (including the reconnect that follows).
    let (player, session) = match state().lock() {
        Ok(mut guard) => {
            let player = guard.player.take();
            let session = guard.session.take();
            guard.events = None;
            guard.access_token = None;
            (player, session)
        }
        Err(_) => return,
    };

    if let Some(player) = player {
        player.stop();
    }

    if let Some(session) = session {
        // Reconnect must not wait for the dead session's teardown. If the shutdown cannot
        // complete it leaks one thread for the lifetime of the process; that is preferable to
        // blocking playback until the app is restarted.
        std::thread::spawn(move || session.shutdown());
    }
}

pub fn create_player(bitrate_kbps: u16) -> Result<(), String> {
    let mut guard = state().lock().map_err(|_| "STATE_LOCK".to_string())?;
    if guard.player.is_some() {
        return Ok(());
    }
    let session = guard
        .session
        .clone()
        .ok_or_else(|| "SESSION_NOT_CONNECTED".to_string())?;

    let _ = audio_rx();
    let tx = AUDIO_TX
        .get()
        .ok_or_else(|| "AUDIO_UNINITIALISED".to_string())?
        .clone();
    let volume = guard.volume.clone();
    let bitrate = match bitrate_kbps {
        0..=96 => Bitrate::Bitrate96,
        97..=160 => Bitrate::Bitrate160,
        _ => Bitrate::Bitrate320,
    };
    let config = PlayerConfig {
        bitrate,
        position_update_interval: Some(Duration::from_millis(500)),
        ..PlayerConfig::default()
    };
    let player = Player::new(config, session, Box::new(SharedVolume(volume)), move || {
        Box::new(PcmSink::new(tx))
    });
    guard.events = Some(player.get_player_event_channel());
    guard.player = Some(player);
    log::info!("engine.create_player created bitrate={bitrate_kbps}");
    Ok(())
}

pub fn load(uri: &str, position_ms: u32, autoplay: bool) -> Result<(), String> {
    log::info!("engine.load begin uri={uri} position_ms={position_ms} autoplay={autoplay}");
    let uri = SpotifyUri::from_uri(uri).map_err(|e| format!("INVALID_URI: {e}"))?;
    if !uri.is_playable() {
        log::warn!("engine.load rejected: not playable");
        return Err("NOT_PLAYABLE".to_string());
    }
    let player = {
        let guard = state().lock().map_err(|_| "STATE_LOCK".to_string())?;
        guard
            .player
            .clone()
            .ok_or_else(|| "PLAYER_NOT_CREATED".to_string())?
    };
    player.load(uri, autoplay, position_ms);
    log::info!("engine.load command queued");
    Ok(())
}

pub fn play() -> Result<(), String> {
    log::info!("engine.play");
    player()?.play();
    Ok(())
}

pub fn pause() -> Result<(), String> {
    log::info!("engine.pause");
    player()?.pause();
    Ok(())
}

pub fn stop() -> Result<(), String> {
    player()?.stop();
    Ok(())
}

pub fn seek(position_ms: u32) -> Result<(), String> {
    player()?.seek(position_ms);
    Ok(())
}

pub fn read_audio(timeout: Duration) -> Option<Vec<u8>> {
    audio_rx().lock().ok()?.recv_timeout(timeout).ok()
}

/// Discards queued PCM. Called when Kotlin stops draining (pause) and before starting a new
/// track, so stale audio from the previous position is not played. `PcmSink::write` no longer
/// blocks indefinitely, so this is a cleanup/safety net rather than the only way to unblock the
/// player thread.
pub fn drain_audio() {
    if let Ok(rx) = audio_rx().lock() {
        let mut drained = 0usize;
        while rx.try_recv().is_ok() {
            drained += 1;
        }
        if drained > 0 {
            log::info!("audio drained {drained} queued buffers");
        }
    }
}

pub fn audio_format() -> String {
    format!("{{\"format\":\"s16le\",\"sampleRate\":{SAMPLE_RATE},\"channels\":{CHANNELS}}}")
}

pub fn poll_event() -> Option<String> {
    use librespot_playback::player::PlayerEvent;
    use serde_json::json;

    let mut guard = state().lock().ok()?;
    let events = guard.events.as_mut()?;
    let event = events.try_recv().ok()?;
    let value = match event {
        PlayerEvent::Stopped { track_id, .. } => json!({"type": "stopped", "uri": uri(&track_id)}),
        PlayerEvent::Loading {
            track_id,
            position_ms,
            ..
        } => {
            json!({"type": "loading", "uri": uri(&track_id), "positionMs": position_ms})
        }
        PlayerEvent::Playing {
            track_id,
            position_ms,
            ..
        } => {
            json!({"type": "playing", "uri": uri(&track_id), "positionMs": position_ms})
        }
        PlayerEvent::Paused {
            track_id,
            position_ms,
            ..
        } => {
            json!({"type": "paused", "uri": uri(&track_id), "positionMs": position_ms})
        }
        PlayerEvent::EndOfTrack { track_id, .. } => {
            json!({"type": "ended", "uri": uri(&track_id)})
        }
        PlayerEvent::Unavailable { track_id, .. } => {
            json!({"type": "unavailable", "uri": uri(&track_id)})
        }
        PlayerEvent::PositionChanged { position_ms, .. }
        | PlayerEvent::PositionCorrection { position_ms, .. }
        | PlayerEvent::Seeked { position_ms, .. } => {
            json!({"type": "position", "positionMs": position_ms})
        }
        PlayerEvent::VolumeChanged { volume } => json!({"type": "volume", "volume": volume}),
        PlayerEvent::ShuffleChanged { shuffle } => json!({"type": "shuffle", "shuffle": shuffle}),
        PlayerEvent::RepeatChanged { context, track } => {
            json!({"type": "repeat", "context": context, "track": track})
        }
        PlayerEvent::AutoPlayChanged { auto_play } => {
            json!({"type": "autoplay", "autoplay": auto_play})
        }
        _ => json!({"type": "other"}),
    };
    Some(value.to_string())
}

fn uri(value: &SpotifyUri) -> String {
    format!(
        "spotify:{}:{}",
        value.item_type(),
        value.to_id().unwrap_or_default()
    )
}

fn player() -> Result<Arc<Player>, String> {
    let guard = state().lock().map_err(|_| "STATE_LOCK".to_string())?;
    guard
        .player
        .clone()
        .ok_or_else(|| "PLAYER_NOT_CREATED".to_string())
}

#[cfg(test)]
mod tests {
    use super::audio_format;

    #[test]
    fn audio_format_is_interleaved_s16_stereo() {
        let format = audio_format();
        assert!(format.contains("\"format\":\"s16le\""));
        assert!(format.contains("\"sampleRate\":44100"));
        assert!(format.contains("\"channels\":2"));
    }
}
