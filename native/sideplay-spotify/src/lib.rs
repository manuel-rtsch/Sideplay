//! JNI surface for Sideplay. All state lives in `engine`; these functions only translate.

mod audio;
mod catalog;
mod engine;

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jbyteArray, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::time::Duration;

fn string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value).ok().map(Into::into)
}

fn catalog_result(env: &mut JNIEnv, result: Result<String, String>) -> jni::sys::jstring {
    let value = match result {
        Ok(value) => value,
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeInit(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    if engine::init().is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeSetTmpDir(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
) {
    if let Some(path) = string(&mut env, &path) {
        engine::set_tmp_dir(&path);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeSetCacheDir(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
    size_limit: jlong,
) {
    if let Some(path) = string(&mut env, &path) {
        engine::set_cache_dir(&path, size_limit.max(0) as u64);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeConnect(
    mut env: JNIEnv,
    _this: JObject,
    token: JString,
) -> jboolean {
    match string(&mut env, &token).map(|token| engine::connect(&token)) {
        Some(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeSetAccessToken(
    mut env: JNIEnv,
    _this: JObject,
    token: JString,
) -> jni::sys::jboolean {
    let Some(token) = string(&mut env, &token) else {
        return JNI_FALSE;
    };
    bool_result(engine::set_access_token(&token))
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeDisconnect(
    _env: JNIEnv,
    _this: JObject,
) {
    engine::disconnect();
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeCreatePlayer(
    _env: JNIEnv,
    _this: JObject,
    bitrate_kbps: jlong,
    normalisation: jboolean,
    gapless: jboolean,
) -> jboolean {
    bool_result(engine::create_player(
        bitrate_kbps.clamp(0, u16::MAX as i64) as u16,
        normalisation != JNI_FALSE,
        gapless != JNI_FALSE,
        false,
    ))
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeRecreatePlayer(
    _env: JNIEnv,
    _this: JObject,
    bitrate_kbps: jlong,
    normalisation: jboolean,
    gapless: jboolean,
) -> jboolean {
    bool_result(engine::create_player(
        bitrate_kbps.clamp(0, u16::MAX as i64) as u16,
        normalisation != JNI_FALSE,
        gapless != JNI_FALSE,
        true,
    ))
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativePreload(
    mut env: JNIEnv,
    _this: JObject,
    uri: JString,
) -> jboolean {
    match string(&mut env, &uri).map(|uri| engine::preload(&uri)) {
        Some(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeLoad(
    mut env: JNIEnv,
    _this: JObject,
    uri: JString,
    position_ms: jlong,
    autoplay: jboolean,
) -> jboolean {
    match string(&mut env, &uri)
        .map(|uri| engine::load(&uri, position_ms.max(0) as u32, autoplay == JNI_TRUE))
    {
        Some(Ok(())) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativePlay(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    bool_result(engine::play())
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativePause(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    bool_result(engine::pause())
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeStop(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    bool_result(engine::stop())
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeSeek(
    _env: JNIEnv,
    _this: JObject,
    position_ms: jlong,
) -> jboolean {
    bool_result(engine::seek(position_ms.max(0) as u32))
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeReadAudio(
    env: JNIEnv,
    _this: JObject,
    timeout_ms: jlong,
) -> jbyteArray {
    let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
    match engine::read_audio(timeout) {
        Some(bytes) => match env.byte_array_from_slice(&bytes) {
            Ok(array) => array.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeDrainAudio(
    _env: JNIEnv,
    _this: JObject,
) {
    engine::drain_audio();
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeAudioFormat(
    env: JNIEnv,
    _this: JObject,
) -> jni::sys::jstring {
    match env.new_string(engine::audio_format()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativePollEvent(
    env: JNIEnv,
    _this: JObject,
) -> jni::sys::jstring {
    match engine::poll_event() {
        Some(event) => match env.new_string(event) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeCatalogLibrary(
    mut env: JNIEnv,
    _this: JObject,
    filter: JString,
    offset: jlong,
    limit: jlong,
) -> jni::sys::jstring {
    let Some(filter) = string(&mut env, &filter) else {
        return std::ptr::null_mut();
    };
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::library(
            session,
            token,
            &filter,
            offset.max(0) as usize,
            limit.max(1) as usize,
        ))
    });
    catalog_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeCatalogCollection(
    mut env: JNIEnv,
    _this: JObject,
    kind: JString,
    id: JString,
    offset: jlong,
    limit: jlong,
) -> jni::sys::jstring {
    let (Some(kind), Some(id)) = (string(&mut env, &kind), string(&mut env, &id)) else {
        return std::ptr::null_mut();
    };
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::collection(
            session,
            token,
            &kind,
            &id,
            offset.max(0) as usize,
            limit.max(1) as usize,
        ))
    });
    catalog_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeCatalogArtist(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
) -> jni::sys::jstring {
    let Some(id) = string(&mut env, &id) else {
        return std::ptr::null_mut();
    };
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::artist(session, token, &id))
    });
    catalog_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeCatalogAlbumsBy(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
    offset: jlong,
    limit: jlong,
) -> jni::sys::jstring {
    let Some(id) = string(&mut env, &id) else {
        return std::ptr::null_mut();
    };
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::albums_by(
            session,
            token,
            &id,
            offset.max(0) as usize,
            limit.max(1) as usize,
        ))
    });
    catalog_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeCatalogTopTracks(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
    offset: jlong,
    limit: jlong,
) -> jni::sys::jstring {
    let Some(id) = string(&mut env, &id) else {
        return std::ptr::null_mut();
    };
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::top_tracks(
            session,
            token,
            &id,
            offset.max(0) as usize,
            limit.max(1) as usize,
        ))
    });
    catalog_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeCatalogSearch(
    mut env: JNIEnv,
    _this: JObject,
    query: JString,
    scopes: JString,
    offset: jlong,
    limit: jlong,
) -> jni::sys::jstring {
    let (Some(query), Some(scopes)) = (string(&mut env, &query), string(&mut env, &scopes)) else {
        return std::ptr::null_mut();
    };
    let scopes: Vec<String> = serde_json::from_str(&scopes).unwrap_or_default();
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::search(
            session,
            token,
            &query,
            offset.max(0) as i32,
            limit as i32,
            &scopes,
        ))
    });
    catalog_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeCollectionContains(
    mut env: JNIEnv,
    _this: JObject,
    set: JString,
    uris: JString,
) -> jni::sys::jstring {
    let (Some(set), Some(uris)) = (string(&mut env, &set), string(&mut env, &uris)) else {
        return std::ptr::null_mut();
    };
    let uris: Vec<String> = serde_json::from_str(&uris).unwrap_or_default();
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::collection_contains(session, token, &set, &uris))
    });
    catalog_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativeCollectionWrite(
    mut env: JNIEnv,
    _this: JObject,
    set: JString,
    uris: JString,
    removed: jboolean,
) -> jboolean {
    let (Some(set), Some(uris)) = (string(&mut env, &set), string(&mut env, &uris)) else {
        return JNI_FALSE;
    };
    let uris: Vec<String> = serde_json::from_str(&uris).unwrap_or_default();
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::collection_write(
            session,
            token,
            &set,
            &uris,
            removed == JNI_TRUE,
        ))
    });
    bool_result(result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativePlaylistSaved(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
) -> jni::sys::jstring {
    let Some(id) = string(&mut env, &id) else {
        return std::ptr::null_mut();
    };
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::playlist_saved(session, token, &id))
            .and_then(|saved| serde_json::to_string(&saved).map_err(|e| e.to_string()))
    });
    catalog_result(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_io_github_manuelrtsch_sideplay_bridge_NativeBridge_nativePlaylistSave(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
    save: jboolean,
) -> jboolean {
    let Some(id) = string(&mut env, &id) else {
        return JNI_FALSE;
    };
    let result = engine::with_session_and_token(|session, token| {
        engine::run(catalog::playlist_save(
            session,
            token,
            &id,
            save == JNI_TRUE,
        ))
    });
    bool_result(result)
}

fn bool_result(result: Result<(), String>) -> jboolean {
    if result.is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
