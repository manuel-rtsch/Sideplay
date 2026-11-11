//! Catalog reads over librespot's authenticated session (spclient + pathfinder).
//!
//! The public Web API has a shared quota that is exhausted by ordinary desktop-client
//! credentials. The first-party endpoints used by the Spotify clients do not share that quota, so
//! Sideplay reads its library, search, artist and collection data through them instead.

use std::collections::HashSet;

use bytes::Bytes;
use futures_util::future::join_all;
use http::header::{HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE};
use http::Method;
use librespot_core::spclient::SpClientError;
use librespot_core::Session;
use librespot_core::SpotifyUri;
use librespot_metadata::image::Images;
use librespot_metadata::{Album, Artist, Episode, Metadata, Playlist, Show, Track};
use librespot_protocol::context::Context;
use librespot_protocol::extended_metadata::{
    BatchedEntityRequest, BatchedExtensionResponse, EntityRequest, ExtensionQuery,
};
use librespot_protocol::extension_kind::ExtensionKind;
use librespot_protocol::playlist4_external::{
    Add, Delta, Item as PlaylistItem, ListChanges, Op, Rem, SelectedListContent,
};
use protobuf::{EnumOrUnknown, Message, MessageField};
use serde::Serialize;
use serde_json::{json, Value};
use std::time::{SystemTime, UNIX_EPOCH};

const PATHFINDER_BASE: &str = "https://api-partner.spotify.com";
const SEARCH_QUERY_HASH: &str = "d9f785900f0710b31c07818d617f4f7600c1e21217e80f5b043d1e78d74e6026";
const COLLECTION_PAGE: i32 = 100;
const COLLECTION_PAGES: usize = 100;
const MAX_PAGE: usize = 200;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct TrackJson {
    id: String,
    title: String,
    artist: String,
    artist_id: String,
    album_id: String,
    art_res: i32,
    duration_ms: i64,
    explicit: bool,
    playable: bool,
    uri: String,
    art_url: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AlbumJson {
    id: String,
    title: String,
    artist: String,
    artist_id: String,
    year: i32,
    art_res: i32,
    tracks: Vec<TrackJson>,
    art_url: Option<String>,
    track_count: i32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct PlaylistJson {
    id: String,
    name: String,
    owner: String,
    owner_id: Option<String>,
    art_res: i32,
    tracks: Vec<TrackJson>,
    art_url: Option<String>,
    track_count: i32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ArtistJson {
    id: String,
    name: String,
    art_res: i32,
    album_ids: Vec<String>,
    art_url: Option<String>,
    album_count: i32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct EpisodeJson {
    id: String,
    title: String,
    show: String,
    show_id: String,
    published: String,
    art_res: i32,
    duration_ms: i64,
    video: bool,
    uri: String,
    art_url: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ShowJson {
    id: String,
    name: String,
    publisher: String,
    art_res: i32,
    episodes: Vec<EpisodeJson>,
    art_url: Option<String>,
    episode_count: i32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct CollectionJson {
    #[serde(rename = "type")]
    kind: String,
    id: String,
    title: String,
    subtitle: String,
    art_res: i32,
    items: Vec<Value>,
    artist_id: Option<String>,
    art_url: Option<String>,
    total_items: i32,
    has_more: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SearchJson {
    tracks: Vec<TrackJson>,
    artists: Vec<ArtistJson>,
    albums: Vec<AlbumJson>,
    playlists: Vec<PlaylistJson>,
    shows: Vec<ShowJson>,
}

fn id_of(uri: &SpotifyUri) -> String {
    uri.to_id().unwrap_or_default()
}

fn uri_of(id: &str, kind: &str) -> String {
    if id.starts_with("spotify:") {
        id.to_string()
    } else {
        format!("spotify:{kind}:{id}")
    }
}

fn spotify_uri(id: &str, kind: &str) -> Result<SpotifyUri, String> {
    SpotifyUri::from_uri(&uri_of(id, kind)).map_err(|e| format!("invalid URI: {e}"))
}

fn image_url(images: &Images) -> Option<String> {
    let preferred = [
        librespot_metadata::image::ImageSize::DEFAULT,
        librespot_metadata::image::ImageSize::SMALL,
        librespot_metadata::image::ImageSize::LARGE,
    ];
    for size in preferred {
        if let Some(image) = images.iter().find(|image| image.size == size) {
            return image
                .id
                .to_base16()
                .ok()
                .map(|id| format!("https://i.scdn.co/image/{id}"));
        }
    }
    images
        .first()
        .and_then(|image| image.id.to_base16().ok())
        .map(|id| format!("https://i.scdn.co/image/{id}"))
}

fn artist_name_and_id(artist: Option<&librespot_metadata::Artist>) -> (String, String) {
    match artist {
        Some(artist) => (artist.name.clone(), id_of(&artist.id)),
        None => ("Unknown artist".to_string(), String::new()),
    }
}

fn track_json(track: &Track) -> TrackJson {
    let (artist, artist_id) = artist_name_and_id(track.artists.first());
    TrackJson {
        id: id_of(&track.id),
        title: track.name.clone(),
        artist,
        artist_id,
        album_id: id_of(&track.album.id),
        art_res: 0,
        duration_ms: track.duration as i64,
        explicit: track.is_explicit,
        playable: track.restrictions.is_empty(),
        uri: uri_of(&id_of(&track.id), "track"),
        art_url: image_url(&track.album.covers),
    }
}

fn album_json(album: &Album, tracks: Vec<TrackJson>) -> AlbumJson {
    let (artist, artist_id) = artist_name_and_id(album.artists.first());
    AlbumJson {
        id: id_of(&album.id),
        title: album.name.clone(),
        artist,
        artist_id,
        year: album.date.year() as i32,
        art_res: 0,
        track_count: album.tracks().count() as i32,
        tracks,
        art_url: image_url(&album.covers),
    }
}

fn playlist_owner(playlist: &Playlist) -> (String, Option<String>) {
    match &playlist.id {
        SpotifyUri::Playlist { user, .. } => {
            let owner = user.clone().unwrap_or_default();
            let id = if owner.is_empty() {
                None
            } else {
                Some(owner.clone())
            };
            (
                if owner.is_empty() {
                    "Spotify".to_string()
                } else {
                    owner
                },
                id,
            )
        }
        _ => ("Spotify".to_string(), None),
    }
}

fn owner_display(owner: &str, current_user: &str) -> String {
    if owner.is_empty() {
        "Spotify".to_string()
    } else if owner == current_user {
        "You".to_string()
    } else {
        owner.to_string()
    }
}

fn playlist_art(playlist: &Playlist) -> Option<String> {
    playlist
        .attributes
        .picture_sizes
        .iter()
        .find(|size| !size.url.is_empty())
        .map(|size| size.url.clone())
}

fn playlist_json(playlist: &Playlist, tracks: Vec<TrackJson>, current_user: &str) -> PlaylistJson {
    let (owner, owner_id) = playlist_owner(playlist);
    PlaylistJson {
        id: id_of(&playlist.id),
        name: playlist.name().to_string(),
        owner: owner_display(&owner, current_user),
        owner_id,
        art_res: 0,
        tracks,
        art_url: playlist_art(playlist),
        track_count: playlist.length.max(0),
    }
}

fn artist_json(artist: &Artist, album_ids: Vec<String>) -> ArtistJson {
    ArtistJson {
        id: id_of(&artist.id),
        name: artist.name.clone(),
        art_res: 0,
        album_count: album_ids.len() as i32,
        album_ids,
        art_url: image_url(&artist.portraits).or_else(|| image_url(&artist.portrait_group)),
    }
}

fn episode_json_with(
    episode: &Episode,
    show_name: &str,
    show_id: &str,
    show_art_url: Option<String>,
) -> EpisodeJson {
    EpisodeJson {
        id: id_of(&episode.id),
        title: episode.name.clone(),
        show: if episode.show_name.is_empty() {
            show_name.to_string()
        } else {
            episode.show_name.clone()
        },
        show_id: show_id.to_string(),
        published: {
            let date = episode.publish_time.as_utc();
            format!(
                "{:04}-{:02}-{:02}",
                date.year(),
                u8::from(date.month()),
                date.day()
            )
        },
        art_res: 0,
        duration_ms: episode.duration as i64,
        video: !episode.videos.is_empty(),
        uri: uri_of(&id_of(&episode.id), "episode"),
        art_url: image_url(&episode.covers).or(show_art_url),
    }
}

fn show_json(show: &Show, episodes: Vec<EpisodeJson>) -> ShowJson {
    ShowJson {
        id: id_of(&show.id),
        name: show.name.clone(),
        publisher: if show.publisher.is_empty() {
            "Podcast".to_string()
        } else {
            show.publisher.clone()
        },
        art_res: 0,
        episode_count: show.episodes.len() as i32,
        episodes,
        art_url: image_url(&show.covers),
    }
}

async fn http_request(
    session: &Session,
    token: &str,
    method: Method,
    url: &str,
    content_type: Option<&str>,
    body: &[u8],
) -> Result<Bytes, String> {
    let mut builder = http::Request::builder()
        .method(method)
        .uri(url)
        .header(AUTHORIZATION, format!("Bearer {token}"));
    if let Some(content_type) = content_type {
        builder = builder.header(CONTENT_TYPE, content_type);
    }
    let request = builder
        .body(Bytes::copy_from_slice(body))
        .map_err(|e| e.to_string())?;
    session
        .http_client()
        .request_body(request)
        .await
        .map_err(|e| e.to_string())
}

async fn sp_request(
    session: &Session,
    token: &str,
    method: Method,
    endpoint: &str,
    content_type: Option<&str>,
    body: &[u8],
) -> Result<Bytes, String> {
    let (host, port) = session
        .spclient()
        .get_accesspoint()
        .await
        .map_err(|e| e.to_string())?;
    let url = if port == 443 {
        format!("https://{host}{endpoint}")
    } else {
        format!("https://{host}:{port}{endpoint}")
    };
    http_request(session, token, method, &url, content_type, body).await
}

async fn metadata_bytes_retry(
    session: &Session,
    token: &str,
    kind: ExtensionKind,
    uri: &SpotifyUri,
) -> Result<Bytes, String> {
    match metadata_bytes(session, token, kind, uri).await {
        Ok(bytes) => Ok(bytes),
        Err(first) => {
            // Spotify's extended-metadata endpoint occasionally answers the first request for a
            // podcast show with a 502. A second request for the same entity succeeds.
            log::warn!("metadata request failed, retrying: {first}");
            metadata_bytes(session, token, kind, uri).await
        }
    }
}

async fn metadata_bytes(
    session: &Session,
    token: &str,
    kind: ExtensionKind,
    uri: &SpotifyUri,
) -> Result<Bytes, String> {
    let request = BatchedEntityRequest {
        entity_request: vec![EntityRequest {
            entity_uri: uri.to_uri().map_err(|e| e.to_string())?,
            query: vec![ExtensionQuery {
                extension_kind: EnumOrUnknown::new(kind),
                ..Default::default()
            }],
            ..Default::default()
        }],
        ..Default::default()
    };
    let body = request.write_to_bytes().map_err(|e| e.to_string())?;
    let bytes = sp_request(
        session,
        token,
        Method::POST,
        "/extended-metadata/v0/extended-metadata",
        Some("application/x-protobuf"),
        &body,
    )
    .await?;
    let mut response =
        BatchedExtensionResponse::parse_from_bytes(&bytes).map_err(|e| e.to_string())?;
    let mut metadata = response
        .extended_metadata
        .pop()
        .ok_or_else(|| SpClientError::ExpectedEntry("extended_metadata").to_string())?;
    let mut data = metadata
        .extension_data
        .pop()
        .ok_or_else(|| SpClientError::ExpectedEntry("extension_data").to_string())?;
    data.extension_data
        .take()
        .map(|data| Bytes::from(data.value))
        .ok_or_else(|| SpClientError::ExpectedEntry("data").to_string())
}

async fn track_get(session: &Session, token: &str, uri: &SpotifyUri) -> Result<Track, String> {
    let bytes = metadata_bytes(session, token, ExtensionKind::TRACK_V4, uri).await?;
    let message =
        librespot_protocol::metadata::Track::parse_from_bytes(&bytes).map_err(|e| e.to_string())?;
    Track::parse(&message, uri).map_err(|e| e.to_string())
}

async fn album_get(session: &Session, token: &str, uri: &SpotifyUri) -> Result<Album, String> {
    let bytes = metadata_bytes(session, token, ExtensionKind::ALBUM_V4, uri).await?;
    let message =
        librespot_protocol::metadata::Album::parse_from_bytes(&bytes).map_err(|e| e.to_string())?;
    Album::parse(&message, uri).map_err(|e| e.to_string())
}

async fn artist_get(session: &Session, token: &str, uri: &SpotifyUri) -> Result<Artist, String> {
    let bytes = metadata_bytes(session, token, ExtensionKind::ARTIST_V4, uri).await?;
    let message = librespot_protocol::metadata::Artist::parse_from_bytes(&bytes)
        .map_err(|e| e.to_string())?;
    Artist::parse(&message, uri).map_err(|e| e.to_string())
}

async fn show_get(session: &Session, token: &str, uri: &SpotifyUri) -> Result<Show, String> {
    let bytes = metadata_bytes_retry(session, token, ExtensionKind::SHOW_V4, uri).await?;
    let message =
        librespot_protocol::metadata::Show::parse_from_bytes(&bytes).map_err(|e| e.to_string())?;
    Show::parse(&message, uri).map_err(|e| e.to_string())
}

async fn episode_get(session: &Session, token: &str, uri: &SpotifyUri) -> Result<Episode, String> {
    let bytes = metadata_bytes_retry(session, token, ExtensionKind::EPISODE_V4, uri).await?;
    let message = librespot_protocol::metadata::Episode::parse_from_bytes(&bytes)
        .map_err(|e| e.to_string())?;
    Episode::parse(&message, uri).map_err(|e| e.to_string())
}

async fn playlist_get(
    session: &Session,
    token: &str,
    uri: &SpotifyUri,
) -> Result<Playlist, String> {
    let id = uri.to_id().map_err(|e| e.to_string())?;
    let bytes = sp_request(
        session,
        token,
        Method::GET,
        &format!("/playlist/v2/playlist/{id}"),
        None,
        &[],
    )
    .await?;
    let content = SelectedListContent::parse_from_bytes(&bytes).map_err(|e| e.to_string())?;
    Playlist::parse(&content, uri).map_err(|e| e.to_string())
}

async fn context_get(session: &Session, token: &str, uri: &str) -> Result<Context, String> {
    let bytes = sp_request(
        session,
        token,
        Method::GET,
        &format!("/context-resolve/v1/{uri}"),
        None,
        &[],
    )
    .await?;
    let text = String::from_utf8(bytes.to_vec()).map_err(|e| e.to_string())?;
    protobuf_json_mapping::parse_from_str::<Context>(&text).map_err(|e| e.to_string())
}

fn context_episode_uris(context: &Context) -> Vec<SpotifyUri> {
    let mut seen = HashSet::new();
    let mut uris = Vec::new();
    for page in context.pages.iter() {
        for track in page.tracks.iter() {
            let Some(uri) = track.uri.as_deref() else {
                continue;
            };
            if !uri.starts_with("spotify:episode:") || !seen.insert(uri.to_string()) {
                continue;
            }
            if let Ok(uri) = SpotifyUri::from_uri(uri) {
                uris.push(uri);
            }
        }
    }
    uris
}

async fn fetch_tracks(
    session: &Session,
    token: &str,
    uris: &[SpotifyUri],
    limit: usize,
) -> Vec<TrackJson> {
    let futures = uris.iter().take(limit).map(|uri| {
        let session = session.clone();
        let token = token.to_string();
        let uri = uri.clone();
        async move {
            track_get(&session, &token, &uri)
                .await
                .ok()
                .map(|track| track_json(&track))
        }
    });
    join_all(futures).await.into_iter().flatten().collect()
}

async fn fetch_albums(
    session: &Session,
    token: &str,
    uris: &[SpotifyUri],
    with_tracks: bool,
) -> Vec<AlbumJson> {
    let futures = uris.iter().map(|uri| {
        let session = session.clone();
        let token = token.to_string();
        let uri = uri.clone();
        async move { album_get(&session, &token, &uri).await.ok() }
    });
    let mut albums = Vec::new();
    for album in join_all(futures).await.into_iter().flatten() {
        let tracks = if with_tracks {
            let uris: Vec<SpotifyUri> = album.tracks().cloned().collect();
            let fetched = fetch_tracks(session, token, &uris, 100).await;
            fetched.into_iter().collect()
        } else {
            Vec::new()
        };
        albums.push(album_json(&album, tracks));
    }
    albums
}

fn encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len() * 2);
    for byte in input.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            _ => {
                out.push('%');
                out.push(char::from(b"0123456789ABCDEF"[(byte >> 4) as usize]));
                out.push(char::from(b"0123456789ABCDEF"[(byte & 0x0F) as usize]));
            }
        }
    }
    out
}

fn pf_str(node: &Value, pointer: &str) -> String {
    node.pointer(pointer)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

fn pf_image(node: Option<&Value>) -> Option<String> {
    let sources = node?.as_array()?;
    let mut best: Option<(i64, String)> = None;
    for source in sources {
        let Some(url) = source.get("url").and_then(Value::as_str) else {
            continue;
        };
        let width = source.get("width").and_then(Value::as_i64).unwrap_or(0);
        if width >= 200
            && best
                .as_ref()
                .is_none_or(|(best_width, _)| width < *best_width)
        {
            best = Some((width, url.to_string()));
        }
    }
    best.map(|(_, url)| url).or_else(|| {
        sources.iter().find_map(|source| {
            source
                .get("url")
                .and_then(Value::as_str)
                .map(str::to_string)
        })
    })
}

fn pf_artists(data: &Value) -> (String, String) {
    let artist = data.pointer("/artists/items/0");
    let name = artist
        .map(|a| pf_str(a, "/profile/name"))
        .unwrap_or_default();
    let id = artist.map(|a| pf_str(a, "/uri")).unwrap_or_default();
    (
        name,
        if id.is_empty() {
            String::new()
        } else {
            id.rsplit(':').next().unwrap_or_default().to_string()
        },
    )
}

fn pf_track(data: &Value) -> Option<TrackJson> {
    let uri = data.get("uri")?.as_str()?.to_string();
    let id = uri.rsplit(':').next().unwrap_or_default().to_string();
    let (artist, artist_id) = pf_artists(data);
    let album_uri = pf_str(data, "/albumOfTrack/uri");
    Some(TrackJson {
        id,
        title: pf_str(data, "/name"),
        artist: if artist.is_empty() {
            "Unknown artist".to_string()
        } else {
            artist
        },
        artist_id,
        album_id: album_uri.rsplit(':').next().unwrap_or_default().to_string(),
        art_res: 0,
        duration_ms: data
            .pointer("/duration/totalMilliseconds")
            .and_then(Value::as_i64)
            .unwrap_or(0),
        explicit: pf_str(data, "/contentRating/label") == "EXPLICIT",
        playable: true,
        uri,
        art_url: pf_image(data.pointer("/albumOfTrack/coverArt/sources")),
    })
}

fn pf_album(data: &Value) -> Option<AlbumJson> {
    let uri = data.get("uri")?.as_str()?;
    let (artist, artist_id) = pf_artists(data);
    Some(AlbumJson {
        id: uri.rsplit(':').next().unwrap_or_default().to_string(),
        title: pf_str(data, "/name"),
        artist: if artist.is_empty() {
            "Unknown artist".to_string()
        } else {
            artist
        },
        artist_id,
        year: pf_str(data, "/date/year").parse().unwrap_or(0),
        art_res: 0,
        tracks: Vec::new(),
        art_url: pf_image(data.pointer("/coverArt/sources")),
        track_count: data
            .pointer("/tracks/totalCount")
            .and_then(Value::as_i64)
            .unwrap_or(0) as i32,
    })
}

fn pf_artist(data: &Value) -> Option<ArtistJson> {
    let uri = data.get("uri")?.as_str()?;
    Some(ArtistJson {
        id: uri.rsplit(':').next().unwrap_or_default().to_string(),
        name: pf_str(data, "/profile/name"),
        art_res: 0,
        album_ids: Vec::new(),
        album_count: 0,
        art_url: pf_image(data.pointer("/visuals/avatarImage/sources")),
    })
}

fn pf_playlist(data: &Value, current_user: &str) -> Option<PlaylistJson> {
    let uri = data.get("uri")?.as_str()?;
    let owner = data.pointer("/ownerV2/data");
    let owner_name = owner.map(|o| pf_str(o, "/name")).unwrap_or_default();
    Some(PlaylistJson {
        id: uri.rsplit(':').next().unwrap_or_default().to_string(),
        name: pf_str(data, "/name"),
        owner: owner_display(&owner_name, current_user),
        owner_id: owner
            .and_then(|o| o.get("uri"))
            .and_then(Value::as_str)
            .map(|u| u.rsplit(':').next().unwrap_or_default().to_string()),
        art_res: 0,
        tracks: Vec::new(),
        art_url: pf_image(data.pointer("/images/items/0/sources")),
        track_count: data
            .pointer("/tracks/totalCount")
            .and_then(Value::as_i64)
            .unwrap_or(0) as i32,
    })
}

fn pf_show(data: &Value) -> Option<ShowJson> {
    let uri = data.get("uri")?.as_str()?;
    Some(ShowJson {
        id: uri.rsplit(':').next().unwrap_or_default().to_string(),
        name: pf_str(data, "/name"),
        publisher: if pf_str(data, "/publisher/name").is_empty() {
            "Podcast".to_string()
        } else {
            pf_str(data, "/publisher/name")
        },
        art_res: 0,
        episodes: Vec::new(),
        art_url: pf_image(data.pointer("/coverArt/sources")),
        episode_count: data
            .pointer("/episodes/totalCount")
            .and_then(Value::as_i64)
            .unwrap_or(0) as i32,
    })
}

fn section_items<'a>(root: &'a Value, name: &str) -> &'a [Value] {
    root.pointer(&format!("/data/searchV2/{name}/items"))
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[])
}

async fn pathfinder_search(
    session: &Session,
    token: &str,
    query: &str,
    offset: i32,
    limit: i32,
) -> Result<Value, String> {
    let variables = json!({
        "searchTerm": query,
        "offset": offset,
        "limit": limit,
        "numberOfTopResults": 5,
        "includeAudiobooks": false,
    });
    let extensions = json!({
        "persistedQuery": {"version": 1, "sha256Hash": SEARCH_QUERY_HASH},
    });
    let url = format!(
        "{PATHFINDER_BASE}/pathfinder/v1/query?operationName=searchDesktop&variables={}&extensions={}",
        encode(&variables.to_string()),
        encode(&extensions.to_string())
    );
    let request = http::Request::builder()
        .method(Method::GET)
        .uri(url)
        .header(AUTHORIZATION, format!("Bearer {token}"))
        .header(ACCEPT, HeaderValue::from_static("application/json"))
        .header("app-platform", HeaderValue::from_static("WebPlayer"))
        .body(Bytes::new())
        .map_err(|e| format!("request: {e}"))?;
    let body = session
        .http_client()
        .request_body(request)
        .await
        .map_err(|e| format!("pathfinder: {e}"))?;
    let value: Value = serde_json::from_slice(&body).map_err(|e| format!("json: {e}"))?;
    if let Some(errors) = value.get("errors") {
        return Err(format!("pathfinder error: {errors}"));
    }
    if value.pointer("/data/searchV2").is_none() {
        return Err("pathfinder returned no searchV2".to_string());
    }
    Ok(value)
}

pub async fn search(
    session: &Session,
    token: &str,
    query: &str,
    offset: i32,
    limit: i32,
    scopes: &[String],
) -> Result<String, String> {
    let want = |scope: &str| {
        scopes.is_empty() || scopes.iter().any(|value| value.eq_ignore_ascii_case(scope))
    };
    match pathfinder_search(session, token, query, offset.max(0), limit.clamp(1, 50)).await {
        Ok(value) => {
            let tracks = if want("Tracks") {
                section_items(&value, "tracksV2")
                    .iter()
                    .filter_map(|item| item.pointer("/item/data"))
                    .filter_map(pf_track)
                    .collect()
            } else {
                Vec::new()
            };
            let artists = if want("Artists") {
                section_items(&value, "artists")
                    .iter()
                    .filter_map(|item| item.get("data"))
                    .filter_map(pf_artist)
                    .collect()
            } else {
                Vec::new()
            };
            let albums = if want("Albums") {
                section_items(&value, "albumsV2")
                    .iter()
                    .filter_map(|item| item.get("data"))
                    .filter_map(pf_album)
                    .collect()
            } else {
                Vec::new()
            };
            let playlists = if want("Playlists") {
                section_items(&value, "playlists")
                    .iter()
                    .filter_map(|item| item.get("data"))
                    .filter_map(|data| pf_playlist(data, &session.username()))
                    .collect()
            } else {
                Vec::new()
            };
            let shows = if want("Shows") {
                section_items(&value, "podcasts")
                    .iter()
                    .filter_map(|item| item.get("data"))
                    .filter_map(pf_show)
                    .collect()
            } else {
                Vec::new()
            };
            let results = SearchJson {
                tracks,
                artists,
                albums,
                playlists,
                shows,
            };
            Ok(serde_json::to_string(&results).map_err(|e| e.to_string())?)
        }
        Err(error) => {
            log::warn!("Pathfinder search failed, using context fallback: {error}");
            let context_uri = format!("spotify:search:{}", query.replace(' ', "+"));
            let context = context_get(session, token, &context_uri)
                .await
                .map_err(|e| format!("search fallback: {e}"))?;
            let mut uris = Vec::new();
            let mut seen = HashSet::new();
            for page in context.pages.iter() {
                for track in page.tracks.iter() {
                    let uri = track.uri();
                    if uri.starts_with("spotify:track:") && seen.insert(uri.to_string()) {
                        uris.push(SpotifyUri::from_uri(uri).map_err(|e| e.to_string())?);
                    }
                }
            }
            let page: Vec<SpotifyUri> = uris
                .into_iter()
                .skip(offset.max(0) as usize)
                .take(limit.clamp(1, 50) as usize)
                .collect();
            let tracks = fetch_tracks(session, token, &page, page.len()).await;
            let results = SearchJson {
                tracks,
                artists: Vec::new(),
                albums: Vec::new(),
                playlists: Vec::new(),
                shows: Vec::new(),
            };
            Ok(serde_json::to_string(&results).map_err(|e| e.to_string())?)
        }
    }
}

#[derive(Debug, Clone)]
struct CollectionItem {
    uri: String,
    added_at: i32,
    is_removed: bool,
}

fn put_varint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

fn put_string(out: &mut Vec<u8>, field: u32, value: &str) {
    put_varint(out, ((field << 3) | 2) as u64);
    put_varint(out, value.len() as u64);
    out.extend_from_slice(value.as_bytes());
}

fn read_varint(bytes: &[u8], index: &mut usize) -> Option<u64> {
    let mut value = 0u64;
    let mut shift = 0;
    while *index < bytes.len() {
        let byte = bytes[*index];
        *index += 1;
        value |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 {
            return Some(value);
        }
        shift += 7;
    }
    None
}

fn parse_collection_item(bytes: &[u8]) -> CollectionItem {
    let mut item = CollectionItem {
        uri: String::new(),
        added_at: 0,
        is_removed: false,
    };
    let mut index = 0;
    while index < bytes.len() {
        let Some(key) = read_varint(bytes, &mut index) else {
            break;
        };
        let field = (key >> 3) as u32;
        let wire = (key & 7) as u8;
        match (field, wire) {
            (1, 2) => {
                let Some(len) = read_varint(bytes, &mut index) else {
                    break;
                };
                let end = index + len as usize;
                if end > bytes.len() {
                    break;
                }
                item.uri = String::from_utf8_lossy(&bytes[index..end]).into_owned();
                index = end;
            }
            (2, 0) => {
                let Some(value) = read_varint(bytes, &mut index) else {
                    break;
                };
                item.added_at = value as i32;
            }
            (3, 0) => {
                let Some(value) = read_varint(bytes, &mut index) else {
                    break;
                };
                item.is_removed = value != 0;
            }
            (_, 0) => {
                let _ = read_varint(bytes, &mut index);
            }
            (_, 1) => {
                index += 8;
            }
            (_, 2) => {
                let Some(len) = read_varint(bytes, &mut index) else {
                    break;
                };
                index += len as usize;
            }
            (_, 5) => {
                index += 4;
            }
            _ => break,
        }
    }
    item
}

fn parse_collection_page(bytes: &[u8]) -> (Vec<CollectionItem>, String) {
    let mut items = Vec::new();
    let mut next_page_token = String::new();
    let mut index = 0;
    while index < bytes.len() {
        let Some(key) = read_varint(bytes, &mut index) else {
            break;
        };
        let field = (key >> 3) as u32;
        let wire = (key & 7) as u8;
        match (field, wire) {
            (1, 2) => {
                let Some(len) = read_varint(bytes, &mut index) else {
                    break;
                };
                let end = index + len as usize;
                if end > bytes.len() {
                    break;
                }
                items.push(parse_collection_item(&bytes[index..end]));
                index = end;
            }
            (2, 2) => {
                let Some(len) = read_varint(bytes, &mut index) else {
                    break;
                };
                let end = index + len as usize;
                if end > bytes.len() {
                    break;
                }
                next_page_token = String::from_utf8_lossy(&bytes[index..end]).into_owned();
                index = end;
            }
            (_, 0) => {
                let _ = read_varint(bytes, &mut index);
            }
            (_, 1) => {
                index += 8;
            }
            (_, 2) => {
                let Some(len) = read_varint(bytes, &mut index) else {
                    break;
                };
                index += len as usize;
            }
            (_, 5) => {
                index += 4;
            }
            _ => break,
        }
    }
    (items, next_page_token)
}

async fn collection_page(
    session: &Session,
    token: &str,
    set: &str,
    offset: usize,
    limit: usize,
) -> Result<Vec<CollectionItem>, String> {
    let username = session.username();
    let mut items = Vec::new();
    let mut skipped = 0usize;
    let mut page_token = String::new();
    let mut seen = HashSet::new();
    for _ in 0..COLLECTION_PAGES {
        let mut body = Vec::new();
        put_string(&mut body, 1, &username);
        put_string(&mut body, 2, set);
        put_string(&mut body, 3, &page_token);
        put_varint(&mut body, (4 << 3) as u64);
        put_varint(&mut body, COLLECTION_PAGE as u64);
        let bytes = sp_request(
            session,
            token,
            Method::POST,
            "/collection/v2/paging",
            Some("application/vnd.collection-v2.spotify.proto"),
            &body,
        )
        .await
        .map_err(|e| format!("collection {set}: {e}"))?;
        let (page_items, next) = parse_collection_page(&bytes);
        let got = page_items.len();
        for item in page_items.into_iter().filter(|item| !item.is_removed) {
            if skipped < offset {
                skipped += 1;
                continue;
            }
            items.push(item);
            if items.len() >= limit {
                return Ok(items);
            }
        }
        if next.is_empty() || got == 0 || !seen.insert(next.clone()) {
            break;
        }
        page_token = next;
    }
    Ok(items)
}

fn collection_uris(items: &[CollectionItem], prefix: &str) -> Vec<SpotifyUri> {
    items
        .iter()
        .filter(|item| item.uri.starts_with(prefix))
        .filter_map(|item| SpotifyUri::from_uri(&item.uri).ok())
        .collect()
}

async fn rootlist_playlists(
    session: &Session,
    token: &str,
    offset: usize,
    limit: usize,
) -> Result<Vec<PlaylistJson>, String> {
    let mut seen = 0usize;
    let mut skipped = 0usize;
    let mut playlists = Vec::new();
    for _ in 0..COLLECTION_PAGES {
        let user = session.username();
        let endpoint = format!(
            "/playlist/v2/user/{user}/rootlist?decorate=revision,attributes,length,owner,capabilities,status_code&from={seen}&length=500"
        );
        let bytes = sp_request(session, token, Method::GET, &endpoint, None, &[])
            .await
            .map_err(|e| format!("rootlist: {e}"))?;
        let content = SelectedListContent::parse_from_bytes(&bytes).map_err(|e| e.to_string())?;
        let items = &content.contents.items;
        let meta = &content.contents.meta_items;
        if items.is_empty() {
            break;
        }
        if content.contents.pos() as usize != seen {
            break;
        }
        for (index, item) in items.iter().enumerate() {
            let uri = item.uri();
            if !uri.starts_with("spotify:playlist:") {
                continue;
            }
            let metadata = meta.get(index);
            let attributes = metadata.and_then(|m| m.attributes.as_ref());
            let name = attributes.map(|a| a.name().to_string()).unwrap_or_default();
            // A few stale rootlist entries carry no name; they cannot be navigated usefully.
            if name.trim().is_empty() {
                continue;
            }
            if skipped < offset {
                skipped += 1;
                continue;
            }
            let owner = metadata
                .map(|m| m.owner_username().to_string())
                .unwrap_or_default();
            let art_url = attributes
                .and_then(|a| a.picture_size.iter().find(|size| !size.url().is_empty()))
                .map(|size| size.url().to_string());
            let owner_id = if owner.is_empty() {
                None
            } else {
                Some(owner.clone())
            };
            playlists.push(PlaylistJson {
                id: uri.rsplit(':').next().unwrap_or_default().to_string(),
                name,
                owner: owner_display(&owner, &user),
                owner_id,
                art_res: 0,
                tracks: Vec::new(),
                art_url,
                track_count: metadata.map(|m| m.length()).unwrap_or(0),
            });
            if playlists.len() >= limit {
                return Ok(playlists);
            }
        }
        seen += items.len();
        if seen >= content.length().max(0) as usize {
            break;
        }
    }
    Ok(playlists)
}

fn collection_item_bytes(uri: &str, added_at: i32, removed: bool) -> Vec<u8> {
    let mut item = Vec::new();
    put_string(&mut item, 1, uri);
    if added_at > 0 {
        put_varint(&mut item, (2 << 3) as u64);
        put_varint(&mut item, added_at as u64);
    }
    if removed {
        put_varint(&mut item, (3 << 3) as u64);
        put_varint(&mut item, 1);
    }
    item
}

fn collection_body(username: &str, set: &str, uris: &[String], removed: bool) -> Vec<u8> {
    let mut body = Vec::new();
    put_string(&mut body, 1, username);
    put_string(&mut body, 2, set);
    let added_at = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs() as i32)
        .unwrap_or(0);
    for uri in uris {
        let item = collection_item_bytes(uri, if removed { 0 } else { added_at }, removed);
        put_varint(&mut body, ((3 << 3) | 2) as u64);
        put_varint(&mut body, item.len() as u64);
        body.extend_from_slice(&item);
    }
    body
}

fn parse_contains(bytes: &[u8]) -> Vec<bool> {
    let mut found = Vec::new();
    let mut index = 0;
    while index < bytes.len() {
        let Some(key) = read_varint(bytes, &mut index) else {
            break;
        };
        let field = (key >> 3) as u32;
        let wire = (key & 7) as u8;
        match (field, wire) {
            (1, 0) => {
                let Some(value) = read_varint(bytes, &mut index) else {
                    break;
                };
                found.push(value != 0);
            }
            (_, 0) => {
                let _ = read_varint(bytes, &mut index);
            }
            (_, 1) => index += 8,
            (_, 2) => {
                let Some(len) = read_varint(bytes, &mut index) else {
                    break;
                };
                index += len as usize;
            }
            (_, 5) => index += 4,
            _ => break,
        }
    }
    found
}

/// Answers in the order of `uris`; used to seed the optimistic save state before a toggle.
pub async fn collection_contains(
    session: &Session,
    token: &str,
    set: &str,
    uris: &[String],
) -> Result<String, String> {
    let body = collection_body(&session.username(), set, uris, false);
    let bytes = sp_request(
        session,
        token,
        Method::POST,
        "/collection/v2/contains",
        Some("application/vnd.collection-v2.spotify.proto"),
        &body,
    )
    .await?;
    let found = parse_contains(&bytes);
    if found.len() != uris.len() {
        return Err("collection contains returned the wrong number of results".to_string());
    }
    serde_json::to_string(&found).map_err(|e| e.to_string())
}

/// Adds or removes items from a collection set. `is_removed` marks a removal.
pub async fn collection_write(
    session: &Session,
    token: &str,
    set: &str,
    uris: &[String],
    removed: bool,
) -> Result<(), String> {
    let body = collection_body(&session.username(), set, uris, removed);
    sp_request(
        session,
        token,
        Method::POST,
        "/collection/v2/write",
        Some("application/vnd.collection-v2.spotify.proto"),
        &body,
    )
    .await?;
    Ok(())
}

async fn rootlist_content(session: &Session, token: &str) -> Result<SelectedListContent, String> {
    let user = session.username();
    let endpoint = format!(
        "/playlist/v2/user/{user}/rootlist?decorate=revision,attributes,length,owner,capabilities,status_code&from=0&length=500"
    );
    let bytes = sp_request(session, token, Method::GET, &endpoint, None, &[])
        .await
        .map_err(|e| format!("rootlist: {e}"))?;
    SelectedListContent::parse_from_bytes(&bytes).map_err(|e| e.to_string())
}

pub async fn playlist_saved(session: &Session, token: &str, id: &str) -> Result<bool, String> {
    let uri = uri_of(id, "playlist");
    let content = rootlist_content(session, token).await?;
    Ok(content
        .contents
        .as_ref()
        .is_some_and(|items| items.items.iter().any(|item| item.uri() == uri)))
}

/// Adds or removes a playlist from the user's rootlist (the "Your Library" playlist list).
pub async fn playlist_save(
    session: &Session,
    token: &str,
    id: &str,
    save: bool,
) -> Result<(), String> {
    let uri = uri_of(id, "playlist");
    let content = rootlist_content(session, token).await?;
    let index = content
        .contents
        .as_ref()
        .and_then(|items| items.items.iter().position(|item| item.uri() == uri));
    if save == index.is_some() {
        return Ok(());
    }

    let mut changes = ListChanges::new();
    changes.base_revision = content.revision.clone();
    let mut delta = Delta::new();
    if save {
        let item = PlaylistItem {
            uri: Some(uri),
            ..Default::default()
        };
        let add = Add {
            items: vec![item],
            add_last: Some(true),
            ..Default::default()
        };
        delta.ops.push(Op {
            kind: Some(EnumOrUnknown::new(
                librespot_protocol::playlist4_external::op::Kind::ADD,
            )),
            add: MessageField::some(add),
            ..Default::default()
        });
    } else {
        let rem = Rem {
            from_index: Some(index.unwrap_or(0) as i32),
            length: Some(1),
            ..Default::default()
        };
        delta.ops.push(Op {
            kind: Some(EnumOrUnknown::new(
                librespot_protocol::playlist4_external::op::Kind::REM,
            )),
            rem: MessageField::some(rem),
            ..Default::default()
        });
    }
    changes.deltas.push(delta);
    let body = changes.write_to_bytes().map_err(|e| e.to_string())?;
    let user = session.username();
    sp_request(
        session,
        token,
        Method::POST,
        &format!("/playlist/v2/user/{user}/rootlist/changes"),
        Some("application/x-www-form-urlencoded"),
        &body,
    )
    .await?;
    Ok(())
}

pub async fn library(
    session: &Session,
    token: &str,
    filter: &str,
    offset: usize,
    limit: usize,
) -> Result<String, String> {
    let limit = limit.clamp(1, MAX_PAGE);
    match filter {
        "LikedSongs" => {
            let items = collection_page(session, token, "collection", offset, limit).await?;
            let uris = collection_uris(&items, "spotify:track:");
            let tracks = fetch_tracks(session, token, &uris, uris.len()).await;
            Ok(serde_json::to_string(&tracks).map_err(|e| e.to_string())?)
        }
        "Playlists" => Ok(serde_json::to_string(
            &rootlist_playlists(session, token, offset, limit).await?,
        )
        .map_err(|e| e.to_string())?),
        "Albums" => {
            let items = collection_page(session, token, "collection", offset, limit).await?;
            let uris = collection_uris(&items, "spotify:album:");
            let albums = fetch_albums(session, token, &uris, false).await;
            Ok(serde_json::to_string(&albums).map_err(|e| e.to_string())?)
        }
        "Artists" => {
            let mut items = collection_page(session, token, "artist", offset, limit).await?;
            if items.is_empty() {
                items = collection_page(session, token, "collection", offset, limit)
                    .await?
                    .into_iter()
                    .filter(|item| item.uri.starts_with("spotify:artist:"))
                    .collect();
            }
            let uris = collection_uris(&items, "spotify:artist:");
            let futures = uris.iter().map(|uri| {
                let session = session.clone();
                let uri = uri.clone();
                async move {
                    Artist::get(&session, &uri)
                        .await
                        .ok()
                        .map(|artist| artist_json(&artist, Vec::new()))
                }
            });
            let artists: Vec<ArtistJson> = join_all(futures).await.into_iter().flatten().collect();
            Ok(serde_json::to_string(&artists).map_err(|e| e.to_string())?)
        }
        "Shows" => {
            let items = collection_page(session, token, "show", offset, limit).await?;
            let uris = collection_uris(&items, "spotify:show:");
            let futures = uris.iter().map(|uri| {
                let session = session.clone();
                let uri = uri.clone();
                async move {
                    Show::get(&session, &uri)
                        .await
                        .ok()
                        .map(|show| show_json(&show, Vec::new()))
                }
            });
            let shows: Vec<ShowJson> = join_all(futures).await.into_iter().flatten().collect();
            Ok(serde_json::to_string(&shows).map_err(|e| e.to_string())?)
        }
        other => Err(format!("unknown library filter: {other}")),
    }
}

pub async fn collection(
    session: &Session,
    token: &str,
    kind: &str,
    id: &str,
    offset: usize,
    limit: usize,
) -> Result<String, String> {
    let limit = limit.clamp(1, MAX_PAGE);
    match kind {
        "Album" => {
            let album = album_get(session, token, &spotify_uri(id, "album")?)
                .await
                .map_err(|e| e.to_string())?;
            let all_uris: Vec<SpotifyUri> = album.tracks().cloned().collect();
            let total = all_uris.len();
            let uris: Vec<SpotifyUri> = all_uris.into_iter().skip(offset).take(limit).collect();
            let tracks = fetch_tracks(session, token, &uris, uris.len()).await;
            let album_json = album_json(&album, tracks);
            let subtitle = format!(
                "{} · {} · {} tracks",
                album_json.artist, album_json.year, album_json.track_count
            );
            let collection = CollectionJson {
                kind: "Album".to_string(),
                id: album_json.id.clone(),
                title: album_json.title.clone(),
                subtitle,
                art_res: 0,
                items: album_json
                    .tracks
                    .iter()
                    .map(|track| serde_json::to_value(track).unwrap_or(Value::Null))
                    .collect(),
                artist_id: Some(album_json.artist_id.clone()).filter(|value| !value.is_empty()),
                art_url: album_json.art_url.clone(),
                total_items: total as i32,
                has_more: offset + album_json.tracks.len() < total,
            };
            Ok(serde_json::to_string(&collection).map_err(|e| e.to_string())?)
        }
        "Playlist" => {
            let playlist = playlist_get(session, token, &spotify_uri(id, "playlist")?)
                .await
                .map_err(|e| e.to_string())?;
            let all_uris: Vec<SpotifyUri> = playlist.tracks().cloned().collect();
            let total = (playlist.length.max(0) as usize).max(all_uris.len());
            let uris: Vec<SpotifyUri> = all_uris.into_iter().skip(offset).take(limit).collect();
            let tracks = fetch_tracks(session, token, &uris, uris.len()).await;
            let playlist_json = playlist_json(&playlist, tracks, &session.username());
            let subtitle = format!(
                "{} · {} tracks",
                playlist_json.owner, playlist_json.track_count
            );
            let collection = CollectionJson {
                kind: "Playlist".to_string(),
                id: playlist_json.id.clone(),
                title: playlist_json.name.clone(),
                subtitle,
                art_res: 0,
                items: playlist_json
                    .tracks
                    .iter()
                    .map(|track| serde_json::to_value(track).unwrap_or(Value::Null))
                    .collect(),
                artist_id: playlist_json.owner_id.clone(),
                art_url: playlist_json.art_url.clone(),
                total_items: total as i32,
                has_more: offset + playlist_json.tracks.len() < total,
            };
            Ok(serde_json::to_string(&collection).map_err(|e| e.to_string())?)
        }
        "Show" => {
            let show_uri = spotify_uri(id, "show")?;
            let show = show_get(session, token, &show_uri).await;
            let (title, publisher, art_url, all_uris) = match show {
                Ok(show) => {
                    let mut uris: Vec<SpotifyUri> = show.episodes.iter().cloned().collect();
                    if uris.is_empty() {
                        // Some shows return no episode list in their metadata. The context
                        // resolver still knows the full episode order.
                        uris = context_get(session, token, &uri_of(id, "show"))
                            .await
                            .map(|context| context_episode_uris(&context))
                            .unwrap_or_default();
                    }
                    (
                        show.name.clone(),
                        if show.publisher.is_empty() {
                            "Podcast".to_string()
                        } else {
                            show.publisher.clone()
                        },
                        image_url(&show.covers),
                        uris,
                    )
                }
                Err(error) => {
                    log::warn!("show metadata failed, using context: {error}");
                    let context = context_get(session, token, &uri_of(id, "show")).await?;
                    let uris = context_episode_uris(&context);
                    let title = context
                        .metadata
                        .get("title")
                        .cloned()
                        .filter(|value| !value.is_empty())
                        .unwrap_or_else(|| "Show".to_string());
                    let publisher = context
                        .metadata
                        .get("publisher")
                        .cloned()
                        .filter(|value| !value.is_empty())
                        .unwrap_or_else(|| "Podcast".to_string());
                    let art_url = context
                        .metadata
                        .get("image_url")
                        .cloned()
                        .filter(|value| !value.is_empty());
                    (title, publisher, art_url, uris)
                }
            };
            let total = all_uris.len();
            let uris: Vec<SpotifyUri> = all_uris.into_iter().skip(offset).take(limit).collect();
            let futures = uris.iter().map(|uri| {
                let session = session.clone();
                let token = token.to_string();
                let uri = uri.clone();
                let title = title.clone();
                let show_id = id.to_string();
                let art_url = art_url.clone();
                async move {
                    episode_get(&session, &token, &uri)
                        .await
                        .ok()
                        .map(|episode| episode_json_with(&episode, &title, &show_id, art_url))
                }
            });
            let episodes: Vec<EpisodeJson> =
                join_all(futures).await.into_iter().flatten().collect();
            let subtitle = format!("{publisher} · {total} episodes");
            let collection = CollectionJson {
                kind: "Show".to_string(),
                id: id.to_string(),
                title,
                subtitle,
                art_res: 0,
                items: episodes
                    .iter()
                    .map(|episode| serde_json::to_value(episode).unwrap_or(Value::Null))
                    .collect(),
                artist_id: None,
                art_url,
                total_items: total as i32,
                has_more: offset + episodes.len() < total,
            };
            Ok(serde_json::to_string(&collection).map_err(|e| e.to_string())?)
        }
        other => Err(format!("unknown collection type: {other}")),
    }
}

pub async fn artist(session: &Session, token: &str, id: &str) -> Result<String, String> {
    let artist = artist_get(session, token, &spotify_uri(id, "artist")?)
        .await
        .map_err(|e| e.to_string())?;
    let album_ids: Vec<String> = artist
        .albums_current()
        .chain(artist.singles_current())
        .map(|uri| uri_of(&id_of(uri), "album"))
        .collect();
    Ok(serde_json::to_string(&artist_json(&artist, album_ids)).map_err(|e| e.to_string())?)
}

pub async fn albums_by(
    session: &Session,
    token: &str,
    id: &str,
    offset: usize,
    limit: usize,
) -> Result<String, String> {
    let artist = artist_get(session, token, &spotify_uri(id, "artist")?)
        .await
        .map_err(|e| e.to_string())?;
    let uris: Vec<SpotifyUri> = artist
        .albums_current()
        .chain(artist.singles_current())
        .skip(offset)
        .take(limit.clamp(1, MAX_PAGE))
        .cloned()
        .collect();
    let mut albums = fetch_albums(session, token, &uris, false).await;
    albums.sort_by(|a, b| b.year.cmp(&a.year).then_with(|| a.title.cmp(&b.title)));
    Ok(serde_json::to_string(&albums).map_err(|e| e.to_string())?)
}

pub async fn top_tracks(
    session: &Session,
    token: &str,
    id: &str,
    offset: usize,
    limit: usize,
) -> Result<String, String> {
    let artist = artist_get(session, token, &spotify_uri(id, "artist")?)
        .await
        .map_err(|e| e.to_string())?;
    let country = session.country();
    let uris: Vec<SpotifyUri> = artist
        .top_tracks
        .for_country(&country)
        .iter()
        .skip(offset)
        .take(limit.clamp(1, MAX_PAGE))
        .cloned()
        .collect();
    let tracks = fetch_tracks(session, token, &uris, uris.len()).await;
    Ok(serde_json::to_string(&tracks).map_err(|e| e.to_string())?)
}

#[cfg(test)]
mod tests {
    use super::owner_display;

    #[test]
    fn owner_display_marks_the_signed_in_user() {
        assert_eq!(owner_display("alice", "alice"), "You");
        assert_eq!(owner_display("bob", "alice"), "bob");
        assert_eq!(owner_display("", "alice"), "Spotify");
    }
}
