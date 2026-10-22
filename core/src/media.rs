//! Photos kept on a journal day (SPEC.md §11–12.2, docs/photos.md).
//!
//! The app picks or takes a photo, does the image work its platform is good
//! at (decoding HEIC, scaling, reading EXIF), and hands the core the original,
//! a preview JPEG and a tiny RGBA thumbnail. The core files the original
//! under `media/<hash>.<ext>` and the preview under `thumbs/<hash>-1024.jpg`,
//! next to the database, and writes one synced `attachments` row.
//!
//! Only the row syncs, never the bytes: other devices get the size, date,
//! caption and a ~25-byte ThumbHash, and draw a blurred tile. When the photo
//! also lives in a library the other device can reach itself, `source_ref`
//! says where ("icloud:<PHCloudIdentifier>"), and that device shows the real
//! photo from its own library. A cloud identifier can take a while to exist
//! (until iCloud has uploaded the photo), so the app records the local asset
//! id with the photo and fills in `source_ref` later through
//! [`Store::photos_awaiting_source_ref`] and [`Store::set_photo_source_ref`].

use std::fs;
use std::path::{Path, PathBuf};

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use rusqlite::{OptionalExtension, Row, params};
use serde_json::json;

use crate::error::{CoreError, Result};
use crate::store::{DEFAULT_JOURNAL, Store, name_id, now_rfc3339};

/// The largest side of the RGBA image a ThumbHash can be made from.
const HASH_MAX_SIDE: u32 = 100;

/// A photo to keep on a day. The app owns the image work; the core owns where
/// the files go and what syncs.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct NewPhoto {
    /// The original, exactly as picked or taken. Moved into the journal's
    /// media folder, so the app must not use this path afterwards.
    pub path: String,
    /// `image/heic`, `image/jpeg`, …
    pub mime: String,
    pub width: u32,
    pub height: u32,
    /// When it was taken, RFC 3339, from the photo's metadata.
    pub taken_at: Option<String>,
    pub lat: Option<f64>,
    pub lng: Option<f64>,
    /// A JPEG of at most 1024 px on the long side, made by the app. Moved in
    /// like `path`.
    pub preview_path: String,
    /// The photo scaled to fit 100 × 100, as RGBA bytes, for the ThumbHash.
    pub hash_rgba: Vec<u8>,
    pub hash_width: u32,
    pub hash_height: u32,
    /// Where other devices can find it themselves, if known now.
    pub source_ref: Option<String>,
    /// This device's own id for it in the system library ("apple:<local
    /// identifier>"), so `source_ref` can be filled in later. Never synced.
    pub local_asset: Option<String>,
}

/// A photo kept on a day, as one device sees it.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct DayPhoto {
    pub id: String,
    pub journal_id: String,
    pub date: String,
    pub mime: Option<String>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub taken_at: Option<String>,
    pub caption: Option<String>,
    /// Base64 ThumbHash: decode with [`thumbhash_image`] for the blurred tile.
    pub thumbhash: Option<String>,
    /// The preview JPEG, when this device has it.
    pub preview_path: Option<String>,
    /// The original, when this device has it.
    pub original_path: Option<String>,
    /// Where this device might find it in its own library ("icloud:<id>").
    pub source_ref: Option<String>,
    /// Added on this device.
    pub from_this_device: bool,
}

/// A photo whose `source_ref` isn't known yet, with this device's asset id.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct PhotoAsset {
    pub id: String,
    pub local_asset: String,
}

/// A decoded ThumbHash, ready to draw scaled up.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct ThumbImage {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

/// Which photo stands for a day in the journal list. The choice syncs; the
/// photo's id is the same on every device (it names the row, not the file),
/// so a device without the file still knows which one was chosen.
#[derive(Debug, Clone, PartialEq, uniffi::Enum, serde::Serialize, serde::Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum PreviewChoice {
    /// The day's first photo, in the order they were taken.
    Default,
    /// No photo, even though the day has some.
    NoPreview,
    /// This photo while it's on the day; the first one once it's removed.
    Photo { id: String },
}

/// How `day_meta.preview_photo` spells "no photo". Photo ids are UUIDs, so
/// it can't be mistaken for one.
const NO_PREVIEW: &str = "none";

/// Decodes a ThumbHash from [`DayPhoto::thumbhash`] into a small RGBA image
/// (about 32 px on the long side) for the app to draw stretched and smooth.
#[uniffi::export]
pub fn thumbhash_image(thumbhash: String) -> Result<ThumbImage> {
    let bytes = B64.decode(thumbhash.as_bytes()).map_err(|_| CoreError::Invalid("thumbhash: not base64".into()))?;
    let (w, h, rgba) =
        thumbhash::thumb_hash_to_rgba(&bytes).map_err(|_| CoreError::Invalid("thumbhash: can't decode".into()))?;
    Ok(ThumbImage { width: w as u32, height: h as u32, rgba })
}

#[uniffi::export]
impl Store {
    /// Keeps a photo on a journal day. Keeping the same file on the same day
    /// again (on any device) lands on the same row, bringing it back if it
    /// had been removed.
    pub fn add_day_photo(&self, journal_id: Option<String>, date: String, photo: NewPhoto) -> Result<DayPhoto> {
        check_day(&date)?;
        check_photo(&photo)?;
        let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());

        let (hash, bytes) = hash_file(Path::new(&photo.path))?;
        let ext = extension(&photo.mime);
        fs::create_dir_all(self.dir.join("media"))?;
        fs::create_dir_all(self.dir.join("thumbs"))?;
        move_file(Path::new(&photo.path), &self.original(&hash, ext))?;
        move_file(Path::new(&photo.preview_path), &self.preview(&hash))?;

        let thumb = thumbhash::rgba_to_thumb_hash(photo.hash_width as usize, photo.hash_height as usize, &photo.hash_rgba);
        let id = name_id("photo", &format!("{journal}:{date}:{hash}"));
        let existing = self.photo_row(&id)?;
        let mut f = json!({
            "journal_id": journal, "date": date, "kind": "photo", "content_hash": hash,
            "mime": photo.mime, "bytes": bytes, "width": photo.width, "height": photo.height,
            "taken_at": photo.taken_at, "lat": photo.lat, "lng": photo.lng,
            "thumbhash": B64.encode(thumb), "origin_device_id": self.device_id(), "deleted_at": null,
        });
        if photo.source_ref.is_some() {
            f["source_ref"] = json!(photo.source_ref);
        }
        if existing.is_none() {
            f["created_at"] = json!(now_rfc3339());
        }
        self.write("attachments", &id, f)?;
        if photo.source_ref.is_none() && photo.local_asset.is_some() {
            self.lock()
                .conn
                .execute("update attachments set local_asset = ?2 where id = ?1", params![id, photo.local_asset])?;
        }
        self.photo_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("photo {id}")))
    }

    /// The photos kept on one day, in the order they were taken.
    pub fn day_photos(&self, journal_id: Option<String>, date: String) -> Result<Vec<DayPhoto>> {
        let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        let rows: Vec<Stored> = {
            let g = self.lock();
            let mut stmt = g.conn.prepare_cached(&format!(
                "select {COLS} from attachments
                 where journal_id = ?1 and date = ?2 and entry_id is null and kind = 'photo' and deleted_at is null
                 order by coalesce(taken_at, created_at), created_at, id"
            ))?;
            stmt.query_map(params![journal, date], stored)?.collect::<std::result::Result<_, _>>()?
        };
        Ok(rows.into_iter().map(|r| self.day_photo(r)).collect())
    }

    /// Takes a photo off its day, on every device. The files go too, unless
    /// the same photo is still kept on another day.
    pub fn remove_day_photo(&self, id: String) -> Result<()> {
        let photo = self.photo_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("photo {id}")))?;
        let hash: Option<String> =
            self.lock().conn.query_row("select content_hash from attachments where id = ?1", [&id], |r| r.get(0))?;
        self.write("attachments", &id, json!({ "deleted_at": now_rfc3339() }))?;

        let Some(hash) = hash else { return Ok(()) };
        let still_used: bool = self.lock().conn.query_row(
            "select exists (select 1 from attachments where content_hash = ?1 and deleted_at is null)",
            [&hash],
            |r| r.get(0),
        )?;
        if !still_used {
            let ext = extension(photo.mime.as_deref().unwrap_or(""));
            for path in [self.original(&hash, ext), self.preview(&hash)] {
                match fs::remove_file(&path) {
                    Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.into()),
                    _ => {}
                }
            }
        }
        Ok(())
    }

    /// The user's caption on a photo; empty clears it.
    pub fn set_photo_caption(&self, id: String, caption: Option<String>) -> Result<()> {
        self.photo_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("photo {id}")))?;
        let caption = caption.map(|c| c.trim().to_string()).filter(|c| !c.is_empty());
        self.write("attachments", &id, json!({ "caption": caption }))
    }

    /// Photos added here whose `source_ref` isn't known yet and whose local
    /// asset id starts with `asset_prefix` ("apple:"), for the app to look up
    /// when it comes to the front.
    pub fn photos_awaiting_source_ref(&self, asset_prefix: String) -> Result<Vec<PhotoAsset>> {
        let g = self.lock();
        let mut stmt = g.conn.prepare_cached(
            "select id, local_asset from attachments
             where deleted_at is null and source_ref is null
               and local_asset is not null and substr(local_asset, 1, length(?1)) = ?1",
        )?;
        let rows = stmt.query_map([asset_prefix], |r| Ok(PhotoAsset { id: r.get(0)?, local_asset: r.get(1)? }))?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Records where other devices can find a photo, once the app knows.
    pub fn set_photo_source_ref(&self, id: String, source_ref: String) -> Result<()> {
        self.photo_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("photo {id}")))?;
        if source_ref.trim().is_empty() {
            return Err(CoreError::Invalid("source_ref is empty".into()));
        }
        self.write("attachments", &id, json!({ "source_ref": source_ref }))?;
        self.lock().conn.execute("update attachments set local_asset = null where id = ?1", [&id])?;
        Ok(())
    }

    /// Chooses the photo that stands for a day in the journal list, or none.
    /// A chosen photo must be on that day.
    pub fn set_day_preview(&self, journal_id: Option<String>, date: String, choice: PreviewChoice) -> Result<()> {
        check_day(&date)?;
        let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        let value = match choice {
            PreviewChoice::Default => None,
            PreviewChoice::NoPreview => Some(NO_PREVIEW.to_string()),
            PreviewChoice::Photo { id } => {
                let on_day = self.photo_row(&id)?.is_some_and(|p| p.journal_id == journal && p.date == date);
                if !on_day {
                    return Err(CoreError::NotFound(format!("photo {id} on {date}")));
                }
                Some(id)
            }
        };
        // The day's own fields go with every write, so the row can always be
        // created from whichever op another device sees first.
        self.write(
            "day_meta",
            &day_meta_id(&journal, &date),
            json!({ "journal_id": journal, "date": date, "preview_photo": value, "deleted_at": null }),
        )
    }

    /// What the user chose for a day, as stored. A chosen photo may since
    /// have been removed; [`Store::day_previews`] then falls back to the first.
    pub fn day_preview_choice(&self, journal_id: Option<String>, date: String) -> Result<PreviewChoice> {
        let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        let value: Option<String> = self
            .lock()
            .conn
            .query_row(
                "select preview_photo from day_meta where id = ?1 and deleted_at is null",
                [day_meta_id(&journal, &date)],
                |r| r.get(0),
            )
            .optional()?
            .flatten();
        Ok(match value.as_deref() {
            None => PreviewChoice::Default,
            Some(NO_PREVIEW) => PreviewChoice::NoPreview,
            Some(id) => PreviewChoice::Photo { id: id.to_string() },
        })
    }

    /// The photo standing for each of `dates` in the journal list: the one
    /// chosen, else the day's first. Days with no photos, or set to no
    /// preview, are left out. One query for a whole page of the list.
    pub fn day_previews(&self, journal_id: Option<String>, dates: Vec<String>) -> Result<Vec<DayPhoto>> {
        if dates.is_empty() {
            return Ok(Vec::new());
        }
        let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        let dates = serde_json::to_string(&dates)?;
        let (rows, choices) = {
            let g = self.lock();
            let mut stmt = g.conn.prepare_cached(&format!(
                "select {COLS} from attachments
                 where journal_id = ?1 and date in (select value from json_each(?2))
                   and entry_id is null and kind = 'photo' and deleted_at is null
                 order by date, coalesce(taken_at, created_at), created_at, id"
            ))?;
            let rows: Vec<Stored> = stmt.query_map(params![journal, dates], stored)?.collect::<std::result::Result<_, _>>()?;
            let mut stmt = g.conn.prepare_cached(
                "select date, preview_photo from day_meta
                 where journal_id = ?1 and date in (select value from json_each(?2))
                   and deleted_at is null and preview_photo is not null",
            )?;
            let choices: std::collections::HashMap<String, String> = stmt
                .query_map(params![journal, dates], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect::<std::result::Result<_, _>>()?;
            (rows, choices)
        };

        // Rows arrive grouped by day, each day's in photo order.
        let mut days: Vec<(String, Vec<Stored>)> = Vec::new();
        for r in rows {
            match days.last_mut() {
                Some((day, photos)) if *day == r.photo.date => photos.push(r),
                _ => days.push((r.photo.date.clone(), vec![r])),
            }
        }
        Ok(days
            .into_iter()
            .filter_map(|(day, mut photos)| {
                let at = match choices.get(&day).map(String::as_str) {
                    Some(NO_PREVIEW) => return None,
                    Some(id) => photos.iter().position(|p| p.photo.id == id).unwrap_or(0),
                    None => 0,
                };
                Some(self.day_photo(photos.swap_remove(at)))
            })
            .collect())
    }
}

fn day_meta_id(journal: &str, date: &str) -> String {
    name_id("day", &format!("{journal}:{date}"))
}

const COLS: &str =
    "id, journal_id, date, mime, width, height, taken_at, caption, thumbhash, source_ref, origin_device_id, content_hash";

struct Stored {
    photo: DayPhoto,
    origin: Option<String>,
    hash: Option<String>,
}

fn stored(r: &Row) -> rusqlite::Result<Stored> {
    Ok(Stored {
        photo: DayPhoto {
            id: r.get(0)?,
            journal_id: r.get::<_, Option<String>>(1)?.unwrap_or_else(|| DEFAULT_JOURNAL.to_string()),
            date: r.get::<_, Option<String>>(2)?.unwrap_or_default(),
            mime: r.get(3)?,
            width: r.get(4)?,
            height: r.get(5)?,
            taken_at: r.get(6)?,
            caption: r.get(7)?,
            thumbhash: r.get(8)?,
            source_ref: r.get(9)?,
            preview_path: None,
            original_path: None,
            from_this_device: false,
        },
        origin: r.get(10)?,
        hash: r.get(11)?,
    })
}

impl Store {
    fn photo_row(&self, id: &str) -> Result<Option<DayPhoto>> {
        let row = {
            let g = self.lock();
            g.conn
                .query_row(
                    &format!("select {COLS} from attachments where id = ?1 and deleted_at is null"),
                    [id],
                    stored,
                )
                .optional()?
        };
        Ok(row.map(|r| self.day_photo(r)))
    }

    /// Fills in what only this device knows: which files it has.
    fn day_photo(&self, row: Stored) -> DayPhoto {
        let mut p = row.photo;
        p.from_this_device = row.origin.as_deref() == Some(self.device_id().as_str());
        if let Some(hash) = row.hash.filter(|h| is_hash(h)) {
            let original = self.original(&hash, extension(p.mime.as_deref().unwrap_or("")));
            let preview = self.preview(&hash);
            p.original_path = original.is_file().then(|| original.to_string_lossy().into_owned());
            p.preview_path = preview.is_file().then(|| preview.to_string_lossy().into_owned());
        }
        p
    }

    fn original(&self, hash: &str, ext: &str) -> PathBuf {
        self.dir.join("media").join(format!("{hash}.{ext}"))
    }

    fn preview(&self, hash: &str) -> PathBuf {
        self.dir.join("thumbs").join(format!("{hash}-1024.jpg"))
    }
}

fn check_day(d: &str) -> Result<()> {
    let fmt = time::macros::format_description!("[year]-[month]-[day]");
    time::Date::parse(d, &fmt).map(|_| ()).map_err(|_| CoreError::Invalid(format!("date {d}: want YYYY-MM-DD")))
}

fn check_photo(p: &NewPhoto) -> Result<()> {
    if !p.mime.starts_with("image/") {
        return Err(CoreError::Invalid(format!("{}: not an image", p.mime)));
    }
    if p.width == 0 || p.height == 0 {
        return Err(CoreError::Invalid("photo has no size".into()));
    }
    let (w, h) = (p.hash_width, p.hash_height);
    if w == 0 || h == 0 || w > HASH_MAX_SIDE || h > HASH_MAX_SIDE || p.hash_rgba.len() != (w * h * 4) as usize {
        return Err(CoreError::Invalid(format!("hash image: want RGBA within {HASH_MAX_SIDE} × {HASH_MAX_SIDE}")));
    }
    if let Some(t) = &p.taken_at {
        time::OffsetDateTime::parse(t, &time::format_description::well_known::Rfc3339)
            .map_err(|_| CoreError::Invalid(format!("taken_at {t}: want RFC 3339")))?;
    }
    Ok(())
}

/// BLAKE3 of the file, hex, and its length.
fn hash_file(path: &Path) -> Result<(String, u64)> {
    let mut hasher = blake3::Hasher::new();
    hasher.update_reader(fs::File::open(path)?)?;
    Ok((hasher.finalize().to_hex().to_string(), fs::metadata(path)?.len()))
}

/// A synced hash names a file, so make sure it can't climb out of media/.
fn is_hash(h: &str) -> bool {
    h.len() == 64 && h.bytes().all(|b| b.is_ascii_hexdigit())
}

fn move_file(from: &Path, to: &Path) -> Result<()> {
    if to.exists() {
        // Same content hash: the file is already here.
        if from != to {
            let _ = fs::remove_file(from);
        }
        return Ok(());
    }
    if fs::rename(from, to).is_err() {
        // Another volume: copy, then drop the original.
        fs::copy(from, to)?;
        let _ = fs::remove_file(from);
    }
    Ok(())
}

fn extension(mime: &str) -> &'static str {
    match mime {
        "image/jpeg" => "jpg",
        "image/heic" => "heic",
        "image/heif" => "heif",
        "image/png" => "png",
        "image/webp" => "webp",
        "image/gif" => "gif",
        "image/avif" => "avif",
        "image/tiff" => "tiff",
        _ => "img",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::IncomingOps;

    fn store(dir: &Path) -> std::sync::Arc<Store> {
        Store::open(dir.join("librelog.sqlite").to_string_lossy().into_owned()).unwrap()
    }

    fn photo(dir: &Path, content: &[u8]) -> NewPhoto {
        let n = uuid::Uuid::new_v4();
        let path = dir.join(format!("in-{n}.jpg"));
        let preview = dir.join(format!("in-{n}-preview.jpg"));
        fs::write(&path, content).unwrap();
        fs::write(&preview, b"preview").unwrap();
        NewPhoto {
            path: path.to_string_lossy().into_owned(),
            mime: "image/jpeg".into(),
            width: 4032,
            height: 3024,
            taken_at: Some("2026-10-18T09:30:00+01:00".into()),
            lat: None,
            lng: None,
            preview_path: preview.to_string_lossy().into_owned(),
            hash_rgba: [200, 100, 50, 255].repeat(4 * 3),
            hash_width: 4,
            hash_height: 3,
            source_ref: None,
            local_asset: Some("apple:ABC/L0/001".into()),
        }
    }

    #[test]
    fn keeps_a_photo_on_a_day_with_its_files() {
        let dir = tempfile::tempdir().unwrap();
        let s = store(dir.path());
        let input = photo(dir.path(), b"pixels");
        let p = s.add_day_photo(None, "2026-10-18".into(), input.clone()).unwrap();

        assert!(p.from_this_device);
        assert!(!Path::new(&input.path).exists(), "original moved in");
        assert_eq!(fs::read(p.original_path.as_ref().unwrap()).unwrap(), b"pixels");
        assert!(p.preview_path.unwrap().ends_with("-1024.jpg"));
        let img = thumbhash_image(p.thumbhash.unwrap()).unwrap();
        assert_eq!(img.rgba.len(), (img.width * img.height * 4) as usize);

        assert_eq!(s.day_photos(None, "2026-10-18".into()).unwrap().len(), 1);
        assert!(s.day_photos(None, "2026-10-19".into()).unwrap().is_empty());
    }

    #[test]
    fn the_same_photo_twice_on_a_day_is_one_row() {
        let dir = tempfile::tempdir().unwrap();
        let s = store(dir.path());
        let a = s.add_day_photo(None, "2026-10-18".into(), photo(dir.path(), b"same")).unwrap();
        s.remove_day_photo(a.id.clone()).unwrap();
        let b = s.add_day_photo(None, "2026-10-18".into(), photo(dir.path(), b"same")).unwrap();
        assert_eq!(a.id, b.id);
        assert!(b.original_path.is_some(), "files come back with it");
        assert_eq!(s.day_photos(None, "2026-10-18".into()).unwrap().len(), 1);
    }

    #[test]
    fn removing_keeps_files_another_day_still_uses() {
        let dir = tempfile::tempdir().unwrap();
        let s = store(dir.path());
        let a = s.add_day_photo(None, "2026-10-18".into(), photo(dir.path(), b"shared")).unwrap();
        let b = s.add_day_photo(None, "2026-10-19".into(), photo(dir.path(), b"shared")).unwrap();
        s.remove_day_photo(a.id).unwrap();
        assert!(Path::new(b.original_path.as_ref().unwrap()).exists());
        s.remove_day_photo(b.id).unwrap();
        assert!(!Path::new(b.original_path.as_ref().unwrap()).exists());
    }

    #[test]
    fn rejects_a_bad_hash_image() {
        let dir = tempfile::tempdir().unwrap();
        let s = store(dir.path());
        let mut p = photo(dir.path(), b"x");
        p.hash_width = 101;
        assert!(matches!(s.add_day_photo(None, "2026-10-18".into(), p), Err(CoreError::Invalid(_))));
    }

    #[test]
    fn source_ref_is_filled_in_later_and_stops_being_asked_for() {
        let dir = tempfile::tempdir().unwrap();
        let s = store(dir.path());
        let p = s.add_day_photo(None, "2026-10-18".into(), photo(dir.path(), b"new")).unwrap();
        let waiting = s.photos_awaiting_source_ref("apple:".into()).unwrap();
        assert_eq!(waiting, vec![PhotoAsset { id: p.id.clone(), local_asset: "apple:ABC/L0/001".into() }]);
        assert!(s.photos_awaiting_source_ref("android:".into()).unwrap().is_empty());

        s.set_photo_source_ref(p.id.clone(), "icloud:XYZ".into()).unwrap();
        assert!(s.photos_awaiting_source_ref("apple:".into()).unwrap().is_empty());
        assert_eq!(s.day_photos(None, "2026-10-18".into()).unwrap()[0].source_ref.as_deref(), Some("icloud:XYZ"));
    }

    fn taken(dir: &Path, content: &[u8], at: &str) -> NewPhoto {
        NewPhoto { taken_at: Some(at.into()), ..photo(dir, content) }
    }

    fn preview_ids(s: &Store, dates: &[&str]) -> Vec<(String, String)> {
        let dates = dates.iter().map(|d| d.to_string()).collect();
        s.day_previews(None, dates).unwrap().into_iter().map(|p| (p.date, p.id)).collect()
    }

    #[test]
    fn a_day_is_previewed_by_its_first_photo_until_another_is_chosen() {
        let dir = tempfile::tempdir().unwrap();
        let s = store(dir.path());
        let day = "2026-10-18";
        let late = s.add_day_photo(None, day.into(), taken(dir.path(), b"late", "2026-10-18T18:00:00+01:00")).unwrap();
        let early = s.add_day_photo(None, day.into(), taken(dir.path(), b"early", "2026-10-18T08:00:00+01:00")).unwrap();
        let other = s.add_day_photo(None, "2026-10-17".into(), photo(dir.path(), b"other")).unwrap();

        // Default: the first taken. Days without photos are left out.
        assert_eq!(s.day_preview_choice(None, day.into()).unwrap(), PreviewChoice::Default);
        assert_eq!(
            preview_ids(&s, &["2026-10-18", "2026-10-17", "2026-10-16"]),
            vec![("2026-10-17".into(), other.id.clone()), (day.into(), early.id.clone())]
        );

        s.set_day_preview(None, day.into(), PreviewChoice::Photo { id: late.id.clone() }).unwrap();
        assert_eq!(preview_ids(&s, &[day]), vec![(day.into(), late.id.clone())]);
        assert_eq!(s.day_preview_choice(None, day.into()).unwrap(), PreviewChoice::Photo { id: late.id.clone() });

        s.set_day_preview(None, day.into(), PreviewChoice::NoPreview).unwrap();
        assert!(preview_ids(&s, &[day]).is_empty());
        assert_eq!(preview_ids(&s, &["2026-10-17"]).len(), 1, "other days keep theirs");

        s.set_day_preview(None, day.into(), PreviewChoice::Default).unwrap();
        assert_eq!(preview_ids(&s, &[day]), vec![(day.into(), early.id)]);
    }

    #[test]
    fn a_removed_choice_falls_back_to_the_first_photo() {
        let dir = tempfile::tempdir().unwrap();
        let s = store(dir.path());
        let day = "2026-10-18";
        let a = s.add_day_photo(None, day.into(), taken(dir.path(), b"a", "2026-10-18T08:00:00+01:00")).unwrap();
        let b = s.add_day_photo(None, day.into(), taken(dir.path(), b"b", "2026-10-18T09:00:00+01:00")).unwrap();
        s.set_day_preview(None, day.into(), PreviewChoice::Photo { id: b.id.clone() }).unwrap();
        s.remove_day_photo(b.id).unwrap();
        assert_eq!(preview_ids(&s, &[day]), vec![(day.into(), a.id)]);
    }

    #[test]
    fn only_a_photo_on_that_day_can_be_chosen() {
        let dir = tempfile::tempdir().unwrap();
        let s = store(dir.path());
        let p = s.add_day_photo(None, "2026-10-17".into(), photo(dir.path(), b"x")).unwrap();
        let wrong_day = s.set_day_preview(None, "2026-10-18".into(), PreviewChoice::Photo { id: p.id });
        assert!(matches!(wrong_day, Err(CoreError::NotFound(_))));
        let unknown = s.set_day_preview(None, "2026-10-18".into(), PreviewChoice::Photo { id: "nope".into() });
        assert!(matches!(unknown, Err(CoreError::NotFound(_))));
        assert!(matches!(s.set_day_preview(None, "18/10".into(), PreviewChoice::NoPreview), Err(CoreError::Invalid(_))));
    }

    #[test]
    fn the_preview_choice_syncs_including_none() {
        let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
        let (a, b) = (store(da.path()), store(db.path()));
        let key = a.generate_journal_key().unwrap();
        a.set_journal_key(key.clone()).unwrap();
        b.set_journal_key(key).unwrap();
        let day = "2026-10-18";
        let mut seq = 0;
        let mut send = |from: &Store, to: &Store| {
            let batch = from.outgoing_batch(100).unwrap().unwrap();
            seq += 1;
            to.apply_incoming(vec![IncomingOps { seq, device_id: from.device_id(), payload: batch.payload }]).unwrap();
            from.push_succeeded(batch.batch_id).unwrap();
        };

        a.add_day_photo(None, day.into(), taken(da.path(), b"one", "2026-10-18T08:00:00+01:00")).unwrap();
        let two = a.add_day_photo(None, day.into(), taken(da.path(), b"two", "2026-10-18T09:00:00+01:00")).unwrap();
        a.set_day_preview(None, day.into(), PreviewChoice::Photo { id: two.id.clone() }).unwrap();
        send(&a, &b);
        let seen = b.day_previews(None, vec![day.into()]).unwrap();
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].id, two.id, "the same photo, though b has no file for it");
        assert!(seen[0].preview_path.is_none() && seen[0].thumbhash.is_some());

        b.set_day_preview(None, day.into(), PreviewChoice::NoPreview).unwrap();
        send(&b, &a);
        assert!(a.day_previews(None, vec![day.into()]).unwrap().is_empty());
        assert_eq!(a.day_preview_choice(None, day.into()).unwrap(), PreviewChoice::NoPreview);
    }

    #[test]
    fn another_device_sees_the_photo_without_its_files() {
        let (da, db) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
        let (a, b) = (store(da.path()), store(db.path()));
        let key = a.generate_journal_key().unwrap();
        a.set_journal_key(key.clone()).unwrap();
        b.set_journal_key(key).unwrap();

        let p = a.add_day_photo(None, "2026-10-18".into(), photo(da.path(), b"far")).unwrap();
        a.set_photo_caption(p.id.clone(), Some(" Harbour ".into())).unwrap();
        a.set_photo_source_ref(p.id.clone(), "icloud:XYZ".into()).unwrap();
        let batch = a.outgoing_batch(100).unwrap().unwrap();
        b.apply_incoming(vec![IncomingOps { seq: 1, device_id: a.device_id(), payload: batch.payload }]).unwrap();

        let seen = &b.day_photos(None, "2026-10-18".into()).unwrap()[0];
        assert_eq!(seen.id, p.id);
        assert_eq!(seen.caption.as_deref(), Some("Harbour"));
        assert_eq!(seen.source_ref.as_deref(), Some("icloud:XYZ"));
        assert!(seen.thumbhash.is_some());
        assert!(seen.original_path.is_none() && seen.preview_path.is_none());
        assert!(!seen.from_this_device);
        assert!(b.photos_awaiting_source_ref("apple:".into()).unwrap().is_empty(), "local asset ids don't sync");
    }
}
