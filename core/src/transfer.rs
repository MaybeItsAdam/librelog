//! Import and export (SPEC.md §16).
//!
//! Export writes the journal as Librelog JSON (lossless, the format to move
//! between installs), a zip of Markdown files with front matter (readable
//! anywhere, Obsidian-friendly), one self-contained HTML page, or plain text.
//!
//! Import reads Librelog JSON, Day One's JSON export (zip or .json), Daylio's
//! CSV export, and Markdown (a file, a folder, or a zip of them, including our
//! own Markdown export). Every imported entry remembers where it came from in
//! `entries.import_ref`, which syncs, so importing the same file twice — on
//! any device — adds nothing the second time. Media isn't imported: sync is
//! text-only (§12.2), and skipped images are counted in the preview.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use rusqlite::{Connection, OptionalExtension, Transaction};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::changes;
use crate::doc::{self, Block};
use crate::error::{CoreError, Result};
use crate::hlc::Clock;
use crate::markdown;
use crate::store::{DEFAULT_JOURNAL, name_id, new_id, now_rfc3339};

/// Imports bigger than this are refused rather than read into memory.
const MAX_IMPORT_BYTES: u64 = 512 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum ExportFormat {
    /// `.json` — everything, losslessly; re-importable.
    Json,
    /// `.zip` of Markdown files with YAML front matter; re-importable.
    Markdown,
    /// `.html` — one self-contained page.
    Html,
    /// `.txt`
    Text,
}

#[derive(Debug, Clone, PartialEq, Serialize, uniffi::Record)]
pub struct ExportSummary {
    pub entries: u32,
    pub path: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, uniffi::Enum)]
pub enum ImportSource {
    Librelog,
    DayOne,
    Daylio,
    Markdown,
    Diarium,
}

#[derive(Debug, Clone, PartialEq, Serialize, uniffi::Record)]
pub struct ImportPreview {
    pub source: ImportSource,
    /// Entries found in the file.
    pub entries: u32,
    /// Of those, how many are already in this journal and will be skipped.
    pub already_imported: u32,
    pub tags: u32,
    pub first_date: Option<String>,
    pub last_date: Option<String>,
    pub skipped_images: u32,
    /// Things worth telling the user before they go ahead.
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, uniffi::Record)]
pub struct ImportResult {
    pub source: ImportSource,
    pub imported: u32,
    pub skipped: u32,
}

#[uniffi::export]
pub fn export_extension(format: ExportFormat) -> String {
    match format {
        ExportFormat::Json => "json",
        ExportFormat::Markdown => "zip",
        ExportFormat::Html => "html",
        ExportFormat::Text => "txt",
    }
    .to_string()
}

fn io(e: impl std::fmt::Display) -> CoreError {
    CoreError::Invalid(format!("file: {e}"))
}

// =============================================================================
// Export
// =============================================================================

#[derive(Debug, Serialize, Deserialize)]
struct ExportedEntry {
    id: String,
    journal_id: String,
    date: String,
    #[serde(default)]
    time: Option<String>,
    #[serde(default)]
    tz: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    end_date: Option<String>,
    #[serde(default)]
    title: Option<String>,
    body: Vec<Block>,
    #[serde(default)]
    rating: Option<f64>,
    #[serde(default)]
    is_favorite: bool,
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    updated_at: Option<String>,
    #[serde(default)]
    import_ref: Option<String>,
    #[serde(default)]
    tags: Vec<ExportedTag>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct ExportedTag {
    name: String,
    #[serde(default)]
    value: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
struct ExportedJournal {
    id: String,
    name: String,
    #[serde(default)]
    color: Option<String>,
}

fn load_for_export(conn: &Connection, journal_id: Option<&str>) -> Result<(Vec<ExportedJournal>, Vec<ExportedEntry>)> {
    let mut journals = Vec::new();
    {
        let mut stmt = conn.prepare("select id, name, color from journals where deleted_at is null order by sort, name")?;
        let rows = stmt.query_map([], |r| Ok(ExportedJournal { id: r.get(0)?, name: r.get(1)?, color: r.get(2)? }))?;
        for j in rows {
            journals.push(j?);
        }
    }

    let mut tags: HashMap<String, Vec<ExportedTag>> = HashMap::new();
    {
        let mut stmt = conn.prepare(
            "select et.entry_id, t.name, et.value from entry_tags et join tags t on t.id = et.tag_id
             where et.deleted_at is null and t.deleted_at is null order by lower(t.name)",
        )?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, ExportedTag { name: r.get(1)?, value: r.get(2)? })))?;
        for row in rows {
            let (entry, tag) = row?;
            tags.entry(entry).or_default().push(tag);
        }
    }

    let mut entries = Vec::new();
    let mut stmt = conn.prepare(
        "select id, journal_id, date, time, tz, title, body, rating, is_favorite, created_at, updated_at, import_ref, end_date
         from entries where deleted_at is null and (?1 is null or journal_id = ?1)
         order by date, coalesce(time, ''), created_at",
    )?;
    let rows = stmt.query_map([journal_id], |r| {
        let id: String = r.get(0)?;
        Ok(ExportedEntry {
            journal_id: r.get(1)?,
            date: r.get(2)?,
            time: r.get(3)?,
            tz: r.get(4)?,
            title: r.get(5)?,
            body: doc::parse(&r.get::<_, String>(6)?),
            rating: r.get(7)?,
            is_favorite: r.get(8)?,
            created_at: r.get(9)?,
            updated_at: r.get(10)?,
            import_ref: r.get(11)?,
            end_date: r.get(12)?,
            tags: Vec::new(),
            id,
        })
    })?;
    for e in rows {
        let mut e = e?;
        e.tags = tags.remove(&e.id).unwrap_or_default();
        entries.push(e);
    }
    Ok((journals, entries))
}

pub fn export(conn: &Connection, format: ExportFormat, path: &str, journal_id: Option<&str>) -> Result<ExportSummary> {
    let (journals, entries) = load_for_export(conn, journal_id)?;
    let titles: HashMap<String, String> = entries
        .iter()
        .map(|e| (e.id.clone(), e.title.clone().unwrap_or_else(|| e.date.clone())))
        .collect();
    let title_of = |id: &str| titles.get(id).cloned();

    // Write beside the target and rename, so a failed export never leaves a
    // half-written file under the real name.
    let tmp = format!("{path}.part");
    let result = (|| -> Result<()> {
        match format {
            ExportFormat::Json => {
                let doc = json!({
                    "format": "librelog",
                    "version": 1,
                    "exported_at": now_rfc3339(),
                    "journals": journals,
                    "entries": entries,
                });
                std::fs::write(&tmp, serde_json::to_vec_pretty(&doc)?).map_err(io)?;
            }
            ExportFormat::Markdown => write_markdown_zip(&tmp, &entries, &journals, &title_of)?,
            ExportFormat::Html => std::fs::write(&tmp, html_page(&entries, &title_of)).map_err(io)?,
            ExportFormat::Text => std::fs::write(&tmp, text_export(&entries)).map_err(io)?,
        }
        std::fs::rename(&tmp, path).map_err(io)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    result?;
    Ok(ExportSummary { entries: entries.len() as u32, path: path.to_string() })
}

fn long_date(ymd: &str) -> String {
    ymd.parse::<jiff::civil::Date>()
        .map(|d| d.strftime("%A %-d %B %Y").to_string())
        .unwrap_or_else(|_| ymd.to_string())
}

fn stars(r: f64) -> String {
    let full = r.floor() as usize;
    let half = r - r.floor() >= 0.5;
    format!("{}{}", "★".repeat(full), if half { "½" } else { "" })
}

/// A file-system-safe slice of a title.
fn slug(s: &str) -> String {
    let cleaned: String = s
        .chars()
        .map(|c| if "/\\:*?\"<>|\n\r\t".contains(c) { ' ' } else { c })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    cleaned.chars().take(60).collect::<String>().trim().to_string()
}

fn yaml_str(s: &str) -> String {
    serde_json::to_string(s).expect("strings serialise") // JSON strings are valid YAML
}

fn markdown_file(e: &ExportedEntry, journal: Option<&str>, title_of: &dyn Fn(&str) -> Option<String>) -> String {
    let mut fm = vec![format!("id: {}", e.id), format!("date: {}", e.date)];
    if let Some(end) = &e.end_date {
        fm.push(format!("end_date: {end}"));
    }
    if let Some(t) = &e.time {
        fm.push(format!("time: \"{t}\""));
    }
    if let Some(t) = &e.title {
        fm.push(format!("title: {}", yaml_str(t)));
    }
    if let Some(r) = e.rating {
        fm.push(format!("rating: {r}"));
    }
    if !e.tags.is_empty() {
        let names: Vec<String> = e.tags.iter().map(|t| yaml_str(&t.name)).collect();
        fm.push(format!("tags: [{}]", names.join(", ")));
    }
    if e.is_favorite {
        fm.push("favorite: true".into());
    }
    if let Some(j) = journal {
        fm.push(format!("journal: {}", yaml_str(j)));
    }
    format!("---\n{}\n---\n\n{}\n", fm.join("\n"), doc::to_markdown(&e.body, title_of))
}

fn write_markdown_zip(
    path: &str,
    entries: &[ExportedEntry],
    journals: &[ExportedJournal],
    title_of: &dyn Fn(&str) -> Option<String>,
) -> Result<()> {
    let names: HashMap<&str, &str> = journals.iter().map(|j| (j.id.as_str(), j.name.as_str())).collect();
    let mut zip = zip::ZipWriter::new(File::create(path).map_err(io)?);
    let opts = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    let mut used = HashSet::new();
    for e in entries {
        let mut stem = e.date.clone();
        if let Some(t) = &e.time {
            stem.push(' ');
            stem.push_str(&t.replace(':', ""));
        }
        if let Some(t) = e.title.as_deref().map(slug).filter(|t| !t.is_empty()) {
            stem.push_str(" - ");
            stem.push_str(&t);
        }
        let dir = format!("Librelog/{}", &e.date[..4.min(e.date.len())]);
        let mut name = format!("{dir}/{stem}.md");
        let mut n = 2;
        while !used.insert(name.clone()) {
            name = format!("{dir}/{stem} ({n}).md");
            n += 1;
        }
        zip.start_file(name, opts).map_err(io)?;
        zip.write_all(markdown_file(e, names.get(e.journal_id.as_str()).copied(), title_of).as_bytes()).map_err(io)?;
    }
    zip.finish().map_err(io)?;
    Ok(())
}

const HTML_STYLE: &str = "
body{margin:0;background:#faf8f4;color:#444054;font:17px/1.6 Arvo,'Rockwell','Roboto Slab',Georgia,serif}
main{max-width:42rem;margin:0 auto;padding:3rem 1.25rem}
h1{font-size:2rem;margin:0 0 2rem}
.day{font:700 10px/1 ui-monospace,'Geist Mono',monospace;letter-spacing:.15em;text-transform:uppercase;color:#6e6b7c;margin:3rem 0 0;padding-bottom:.5rem;border-bottom:1px solid #e6e4ea}
article{padding:1.25rem 0;border-bottom:1px solid #e6e4ea}
.meta{font:12px ui-monospace,'Geist Mono',monospace;color:#6e6b7c}
.meta .stars{color:#ffbf00}
.tag{display:inline-block;font:12px system-ui,sans-serif;background:#edebef;border-radius:6px;padding:1px 6px;margin-right:4px}
h2{font-size:1.3rem;margin:.25rem 0}
h3,h4{margin:1rem 0 .25rem}
blockquote{margin:.5rem 0;padding-left:1rem;border-left:2px solid #d8d5dd;color:#6e6b7c}
pre{background:#edebef;border-radius:6px;padding:.5rem .75rem;overflow:auto}
mark{background:rgba(255,191,0,.3)}
a{color:inherit}
li.check{list-style:none;margin-left:-1.25rem}
@media (prefers-color-scheme:dark){body{background:#1c1a23;color:#f5f4f7}.day,.meta,blockquote{color:#b6b3bf}.day,article{border-color:#34313f}.tag,pre{background:#2d2b38}}
";

fn html_page(entries: &[ExportedEntry], title_of: &dyn Fn(&str) -> Option<String>) -> String {
    use markdown::escape_html as esc;
    let mut body = String::new();
    let mut day = "";
    for e in entries.iter().rev() {
        if e.date != day {
            day = &e.date;
            body.push_str(&format!("<h2 class=\"day\">{}</h2>\n", esc(&long_date(day))));
        }
        let mut meta = Vec::new();
        if let Some(end) = &e.end_date {
            meta.push(format!("until {}", esc(&long_date(end))));
        }
        if let Some(t) = &e.time {
            meta.push(esc(t));
        }
        if let Some(r) = e.rating {
            meta.push(format!("<span class=\"stars\">{}</span>", stars(r)));
        }
        if e.is_favorite {
            meta.push("♥".into());
        }
        body.push_str(&format!("<article id=\"e-{}\">\n<div class=\"meta\">{}</div>\n", esc(&e.id), meta.join(" · ")));
        if let Some(t) = &e.title {
            body.push_str(&format!("<h2>{}</h2>\n", esc(t)));
        }
        body.push_str(&markdown::to_html(&e.body, title_of));
        if !e.tags.is_empty() {
            body.push_str("<p>");
            for t in &e.tags {
                body.push_str(&format!("<span class=\"tag\">{}</span>", esc(&t.name)));
            }
            body.push_str("</p>\n");
        }
        body.push_str("</article>\n");
    }
    format!(
        "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>Journal</title>\n<style>{HTML_STYLE}</style>\n</head>\n<body>\n<main>\n<h1>Journal</h1>\n{body}</main>\n</body>\n</html>\n"
    )
}

fn text_export(entries: &[ExportedEntry]) -> String {
    let mut out = String::new();
    let mut day = "";
    for e in entries {
        if e.date != day {
            day = &e.date;
            if !out.is_empty() {
                out.push('\n');
            }
            out.push_str(&format!("{}\n{}\n\n", long_date(day), "=".repeat(long_date(day).chars().count())));
        }
        let mut head = Vec::new();
        if let Some(end) = &e.end_date {
            head.push(format!("until {}", long_date(end)));
        }
        if let Some(t) = &e.time {
            head.push(t.clone());
        }
        if let Some(t) = &e.title {
            head.push(t.clone());
        }
        if let Some(r) = e.rating {
            head.push(stars(r));
        }
        if !head.is_empty() {
            out.push_str(&head.join("  "));
            out.push('\n');
        }
        out.push_str(&doc::plain_text(&e.body));
        out.push_str("\n\n");
    }
    out
}

// =============================================================================
// Import
// =============================================================================

/// One entry read from a file, in source-independent form.
#[derive(Debug, Clone)]
struct Incoming {
    reference: String,
    /// Kept from a Librelog export so links between entries survive.
    id: Option<String>,
    journal_id: Option<String>,
    date: String,
    time: Option<String>,
    end_date: Option<String>,
    tz: Option<String>,
    title: Option<String>,
    blocks: Vec<Block>,
    rating: Option<f64>,
    favorite: bool,
    tags: Vec<(String, Option<String>)>,
    created_at: Option<String>,
}

struct Read_ {
    source: ImportSource,
    entries: Vec<Incoming>,
    journals: Vec<ExportedJournal>,
    skipped_images: u32,
    warnings: Vec<String>,
}

/// A file read into memory: its path inside an archive (or on disk) and bytes.
struct Doc {
    name: String,
    bytes: Vec<u8>,
}

fn read_input(path: &str) -> Result<Vec<Doc>> {
    let p = Path::new(path);
    let meta = std::fs::metadata(p).map_err(io)?;
    if meta.is_dir() {
        let mut out = Vec::new();
        let mut total = 0;
        walk(p, p, &mut out, &mut total)?;
        return Ok(out);
    }
    if meta.len() > MAX_IMPORT_BYTES {
        return Err(CoreError::Invalid("file is too large to import".into()));
    }
    let bytes = std::fs::read(p).map_err(io)?;
    if bytes.starts_with(b"PK\x03\x04") {
        return read_zip(bytes);
    }
    let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    Ok(vec![Doc { name, bytes }])
}

fn walk(root: &Path, dir: &Path, out: &mut Vec<Doc>, total: &mut u64) -> Result<()> {
    let mut items: Vec<PathBuf> = std::fs::read_dir(dir).map_err(io)?.filter_map(|e| e.ok().map(|e| e.path())).collect();
    items.sort();
    for path in items {
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        if name.starts_with('.') {
            continue;
        }
        if path.is_dir() {
            walk(root, &path, out, total)?;
        } else if is_text_name(&name) || name.ends_with(".json") || name.ends_with(".csv") {
            *total += std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
            if *total > MAX_IMPORT_BYTES {
                return Err(CoreError::Invalid("folder is too large to import".into()));
            }
            let rel = path.strip_prefix(root).unwrap_or(&path).to_string_lossy().into_owned();
            out.push(Doc { name: rel, bytes: std::fs::read(&path).map_err(io)? });
        }
    }
    Ok(())
}

fn read_zip(bytes: Vec<u8>) -> Result<Vec<Doc>> {
    let mut ar = zip::ZipArchive::new(std::io::Cursor::new(bytes)).map_err(io)?;
    let mut out = Vec::new();
    let mut total = 0u64;
    for i in 0..ar.len() {
        let mut f = ar.by_index(i).map_err(io)?;
        if f.is_dir() {
            continue;
        }
        // enclosed_name rejects absolute paths and `..`.
        let Some(name) = f.enclosed_name() else { continue };
        let name = name.to_string_lossy().into_owned();
        let base = name.rsplit('/').next().unwrap_or(&name);
        if base.starts_with('.') || name.starts_with("__MACOSX") {
            continue;
        }
        if !(is_text_name(&name) || name.ends_with(".json") || name.ends_with(".csv")) {
            continue; // photos, audio and so on: text-only import
        }
        total += f.size();
        if total > MAX_IMPORT_BYTES {
            return Err(CoreError::Invalid("archive is too large to import".into()));
        }
        let mut buf = Vec::with_capacity(f.size() as usize);
        f.read_to_end(&mut buf).map_err(io)?;
        out.push(Doc { name, bytes: buf });
    }
    Ok(out)
}

fn is_text_name(name: &str) -> bool {
    let n = name.to_lowercase();
    n.ends_with(".md") || n.ends_with(".markdown") || n.ends_with(".txt")
}

fn text(bytes: &[u8]) -> String {
    let s = String::from_utf8_lossy(bytes);
    s.strip_prefix('\u{feff}').unwrap_or(&s).to_string()
}

/// Works out what the input is and reads it.
fn read_any(path: &str) -> Result<Read_> {
    // A Diarium backup is its SQLite database, whatever the file is called.
    if is_sqlite(path) {
        return read_diarium(path);
    }
    let docs = read_input(path)?;
    if docs.is_empty() {
        return Err(CoreError::Invalid("nothing to import in that file".into()));
    }

    // A JSON document decides by its shape.
    for d in docs.iter().filter(|d| d.name.to_lowercase().ends_with(".json")) {
        let Ok(v) = serde_json::from_slice::<Value>(&d.bytes) else { continue };
        if v.get("format").and_then(Value::as_str) == Some("librelog") {
            return read_librelog(v);
        }
        if v.get("entries").is_some_and(Value::is_array) && v.get("metadata").is_some() {
            // Day One puts one JSON per journal in its zip; read them all.
            let journals: Vec<Value> = docs
                .iter()
                .filter(|d| d.name.to_lowercase().ends_with(".json"))
                .filter_map(|d| serde_json::from_slice::<Value>(&d.bytes).ok())
                .filter(|v| v.get("entries").is_some_and(Value::is_array))
                .collect();
            return read_day_one(&journals);
        }
    }

    if let Some(d) = docs.iter().find(|d| d.name.to_lowercase().ends_with(".csv")) {
        return read_daylio(&d.bytes);
    }

    let md: Vec<&Doc> = docs.iter().filter(|d| is_text_name(&d.name)).collect();
    if !md.is_empty() {
        return read_markdown(&md);
    }
    Err(CoreError::Invalid(
        "not a format Librelog can import (Librelog JSON, Day One, Daylio CSV, Diarium backup or Markdown)".into(),
    ))
}

// --- Librelog JSON -------------------------------------------------------------

fn read_librelog(v: Value) -> Result<Read_> {
    #[derive(Deserialize)]
    struct File_ {
        #[serde(default)]
        journals: Vec<ExportedJournal>,
        entries: Vec<ExportedEntry>,
    }
    let f: File_ = serde_json::from_value(v)?;
    let entries = f
        .entries
        .into_iter()
        .map(|e| Incoming {
            reference: e.import_ref.clone().unwrap_or_else(|| format!("librelog:{}", e.id)),
            id: Some(e.id),
            journal_id: Some(e.journal_id),
            date: e.date,
            time: e.time,
            end_date: e.end_date,
            tz: e.tz,
            title: e.title,
            blocks: e.body,
            rating: e.rating,
            favorite: e.is_favorite,
            tags: e.tags.into_iter().map(|t| (t.name, t.value)).collect(),
            created_at: e.created_at,
        })
        .collect();
    Ok(Read_ { source: ImportSource::Librelog, entries, journals: f.journals, skipped_images: 0, warnings: vec![] })
}

// --- Day One -------------------------------------------------------------------

fn read_day_one(journals: &[Value]) -> Result<Read_> {
    let mut entries = Vec::new();
    let mut images = 0;
    let mut warnings = Vec::new();
    let mut bad_zone = false;
    for j in journals {
        for e in j["entries"].as_array().into_iter().flatten() {
            let Some(uuid) = e["uuid"].as_str() else { continue };
            let Some(created) = e["creationDate"].as_str() else { continue };
            let Ok(ts) = created.parse::<jiff::Timestamp>() else { continue };
            let zone = e["timeZone"].as_str();
            let zoned = match zone.map(|z| ts.in_tz(z)) {
                Some(Ok(z)) => z,
                _ => {
                    bad_zone |= zone.is_some();
                    ts.to_zoned(jiff::tz::TimeZone::UTC)
                }
            };
            let parsed = markdown::from_markdown(e["text"].as_str().unwrap_or(""));
            images += parsed.skipped_images;
            entries.push(Incoming {
                reference: format!("dayone:{uuid}"),
                id: None,
                journal_id: None,
                date: zoned.date().to_string(),
                time: Some(zoned.strftime("%H:%M").to_string()),
                end_date: None,
                tz: zone.map(str::to_string),
                title: None,
                blocks: parsed.blocks,
                rating: None,
                favorite: e["starred"].as_bool().unwrap_or(false),
                tags: e["tags"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(Value::as_str)
                    .map(|t| (t.to_string(), None))
                    .collect(),
                created_at: Some(ts.to_string()),
            });
        }
    }
    if bad_zone {
        warnings.push("Some Day One entries had an unknown time zone; their times are shown in UTC.".into());
    }
    Ok(Read_ { source: ImportSource::DayOne, entries, journals: vec![], skipped_images: images, warnings })
}

// --- Daylio --------------------------------------------------------------------

fn read_daylio(bytes: &[u8]) -> Result<Read_> {
    let body = text(bytes);
    let mut rdr = csv::ReaderBuilder::new().flexible(true).from_reader(body.as_bytes());
    let headers: Vec<String> = rdr.headers().map_err(io)?.iter().map(|h| h.trim().to_lowercase()).collect();
    let col = |name: &str| headers.iter().position(|h| h == name);
    let (Some(c_date), Some(c_mood)) = (col("full_date"), col("mood")) else {
        return Err(CoreError::Invalid("that CSV isn't a Daylio export (no full_date and mood columns)".into()));
    };
    let (c_time, c_acts, c_title, c_note) = (col("time"), col("activities"), col("note_title"), col("note"));

    let mut entries = Vec::new();
    let mut seen: HashMap<String, u32> = HashMap::new();
    let mut custom_moods = HashSet::new();
    for rec in rdr.records() {
        let rec = rec.map_err(io)?;
        let get = |c: Option<usize>| c.and_then(|c| rec.get(c)).map(str::trim).unwrap_or("");
        let date = get(Some(c_date)).to_string();
        if date.parse::<jiff::civil::Date>().is_err() {
            continue;
        }
        let time = clock_24h(get(c_time));
        let mood = get(Some(c_mood));
        let rating = match mood.to_lowercase().as_str() {
            "rad" => Some(5.0),
            "good" => Some(4.0),
            "meh" => Some(3.0),
            "bad" => Some(2.0),
            "awful" => Some(1.0),
            _ => None,
        };
        let mut tags: Vec<(String, Option<String>)> = get(c_acts)
            .split('|')
            .map(str::trim)
            .filter(|t| !t.is_empty())
            .map(|t| (t.to_string(), None))
            .collect();
        if rating.is_none() && !mood.is_empty() {
            custom_moods.insert(mood.to_string());
            tags.push((mood.to_string(), None));
        }
        let note = strip_html(get(c_note));
        let key = format!("daylio:{date}T{}", time.clone().unwrap_or_default());
        let n = seen.entry(key.clone()).or_insert(0);
        *n += 1;
        let reference = if *n == 1 { key } else { format!("{key}#{n}") };
        let title = Some(get(c_title).to_string()).filter(|t| !t.is_empty());
        entries.push(Incoming {
            reference,
            id: None,
            journal_id: None,
            date,
            time,
            end_date: None,
            tz: None,
            title,
            blocks: markdown::from_markdown(&note).blocks,
            rating,
            favorite: false,
            tags,
            created_at: None,
        });
    }
    let mut warnings = Vec::new();
    if !custom_moods.is_empty() {
        let mut m: Vec<_> = custom_moods.into_iter().collect();
        m.sort();
        warnings.push(format!("Custom moods become tags rather than a rating: {}.", m.join(", ")));
    }
    Ok(Read_ { source: ImportSource::Daylio, entries, journals: vec![], skipped_images: 0, warnings })
}

fn is_sqlite(path: &str) -> bool {
    let mut head = [0u8; 16];
    File::open(path).and_then(|mut f| f.read_exact(&mut head)).is_ok() && &head == b"SQLite format 3\0"
}

/// .NET ticks (100 ns since 0001-01-01, local wall time) → date and HH:MM.
fn ticks_local(ticks: i64) -> Option<(String, String)> {
    let base = jiff::civil::date(1, 1, 1).at(0, 0, 0, 0);
    let secs = ticks / 10_000_000;
    let t = base.checked_add(jiff::Span::new().seconds(secs)).ok()?;
    Some((t.date().to_string(), format!("{:02}:{:02}", t.hour(), t.minute())))
}

/// Diarium's rich text is a run of `<p>`s with a little inline markup;
/// turn it into Markdown so the usual parser makes the blocks.
fn diarium_markdown(html: &str) -> String {
    let mut out = String::new();
    let mut rest = html;
    let mut list: Vec<(bool, u32)> = Vec::new(); // (numbered, next number)
    while let Some(i) = rest.find('<') {
        out.push_str(&rest[..i]);
        let Some(j) = rest[i..].find('>') else {
            out.push_str(&rest[i..]);
            rest = "";
            break;
        };
        let raw = rest[i + 1..i + j].trim().to_lowercase();
        let name: String = raw.trim_start_matches('/').chars().take_while(|c| c.is_ascii_alphanumeric()).collect();
        let closing = raw.starts_with('/');
        match (name.as_str(), closing) {
            ("p" | "div", true) | ("br", _) => out.push('\n'),
            ("h1" | "h2" | "h3", false) => out.push_str("## "),
            ("h1" | "h2" | "h3", true) => out.push('\n'),
            ("b" | "strong", _) => out.push_str("**"),
            ("i" | "em", _) => out.push('*'),
            ("s" | "strike" | "del", _) => out.push_str("~~"),
            ("ul", false) => list.push((false, 1)),
            ("ol", false) => list.push((true, 1)),
            ("ul" | "ol", true) => {
                list.pop();
            }
            ("li", false) => {
                if !out.is_empty() && !out.ends_with('\n') {
                    out.push('\n');
                }
                match list.last_mut() {
                    Some((true, n)) => {
                        out.push_str(&format!("{n}. "));
                        *n += 1;
                    }
                    _ => out.push_str("- "),
                }
            }
            ("li", true) => out.push('\n'),
            ("blockquote", false) => out.push_str("> "),
            _ => {}
        }
        rest = &rest[i + j + 1..];
    }
    out.push_str(rest);
    html_unescape(&out)
}

fn read_diarium(path: &str) -> Result<Read_> {
    let not_diarium = || CoreError::Invalid("this database isn't a Diarium backup".into());
    let db = Connection::open_with_flags(path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY).map_err(io)?;
    let has = |t: &str| -> bool {
        db.query_row("select 1 from sqlite_master where type = 'table' and name = ?1", [t], |_| Ok(()))
            .optional()
            .ok()
            .flatten()
            .is_some()
    };
    if !has("Entries") {
        return Err(not_diarium());
    }
    let mut tags: HashMap<i64, Vec<(String, Option<String>)>> = HashMap::new();
    if has("Tags") && has("EntryTags") {
        let mut stmt = db
            .prepare("select et.DiaryEntryId, t.Value, et.TrackingValue from EntryTags et join Tags t on t.DiaryTagId = et.DiaryTagId")
            .map_err(|_| not_diarium())?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get::<_, Option<String>>(2)?)))?;
        for row in rows {
            let (id, name, value) = row?;
            let value = value.filter(|v| !v.trim().is_empty());
            tags.entry(id).or_default().push((name.trim().to_string(), value));
        }
    }
    let photos: u32 = if has("Media") {
        db.query_row("select count(*) from Media", [], |r| r.get(0))?
    } else {
        0
    };
    let mut stmt = db
        .prepare("select DiaryEntryId, Heading, Text, Rating from Entries order by DiaryEntryId")
        .map_err(|_| not_diarium())?;
    let rows = stmt.query_map([], |r| {
        Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, i64>(3)?))
    })?;
    let mut entries = Vec::new();
    let mut undated = 0;
    for row in rows {
        let (id, heading, html, rating) = row?;
        let Some((date, time)) = ticks_local(id) else {
            undated += 1;
            continue;
        };
        let doc = markdown::from_markdown(&diarium_markdown(&html));
        let heading = heading.trim();
        entries.push(Incoming {
            reference: format!("diarium:{id}"),
            id: None,
            journal_id: None,
            date,
            time: Some(time),
            end_date: None,
            tz: None,
            title: (!heading.is_empty()).then(|| heading.to_string()),
            blocks: doc.blocks,
            rating: (1..=5).contains(&rating).then_some(rating as f64),
            favorite: false,
            tags: tags.remove(&id).unwrap_or_default(),
            created_at: None,
        });
    }
    let mut warnings = Vec::new();
    if undated > 0 {
        warnings.push(format!("{undated} entries had no readable date and were left out."));
    }
    Ok(Read_ { source: ImportSource::Diarium, entries, journals: vec![], skipped_images: photos, warnings })
}

/// "8:05 PM" / "20:05" → "20:05".
fn clock_24h(s: &str) -> Option<String> {
    let s = s.trim();
    if s.is_empty() {
        return None;
    }
    let upper = s.to_uppercase();
    let (hm, pm) = if let Some(x) = upper.strip_suffix("PM") {
        (x.trim().to_string(), Some(true))
    } else if let Some(x) = upper.strip_suffix("AM") {
        (x.trim().to_string(), Some(false))
    } else {
        (upper.clone(), None)
    };
    let (h, m) = hm.split_once(':')?;
    let (mut h, m): (u32, u32) = (h.trim().parse().ok()?, m.trim().get(..2)?.parse().ok()?);
    match pm {
        Some(true) if h < 12 => h += 12,
        Some(false) if h == 12 => h = 0,
        _ => {}
    }
    (h < 24 && m < 60).then(|| format!("{h:02}:{m:02}"))
}

/// Daylio notes are HTML-ish: line breaks as <br>, a few entities.
fn strip_html(s: &str) -> String {
    let mut out = String::new();
    let mut rest = s;
    while let Some(i) = rest.find('<') {
        out.push_str(&rest[..i]);
        match rest[i..].find('>') {
            Some(j) => {
                let tag = rest[i + 1..i + j].trim().to_lowercase();
                if tag.starts_with("br") || tag == "/p" || tag == "/div" {
                    out.push('\n');
                }
                rest = &rest[i + j + 1..];
            }
            None => {
                out.push_str(&rest[i..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    html_unescape(&out)
}

fn html_unescape(s: &str) -> String {
    s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

// --- Markdown ------------------------------------------------------------------

/// Splits `---\nkey: value\n---\nbody` into a simple key → value map and the
/// body. Values are kept as raw strings; lists in `[a, b]` or `- a` form are
/// joined with \u{1f}.
fn front_matter(src: &str) -> (BTreeMap<String, String>, &str) {
    let mut map = BTreeMap::new();
    let Some(rest) = src.strip_prefix("---\n").or_else(|| src.strip_prefix("---\r\n")) else { return (map, src) };
    let Some(end) = rest.find("\n---") else { return (map, src) };
    let header = &rest[..end];
    let body = rest[end + 4..].trim_start_matches(['\r', '\n']);
    let mut last_key: Option<String> = None;
    for line in header.lines() {
        if let Some(item) = line.trim_start().strip_prefix("- ")
            && line.starts_with([' ', '-'])
            && let Some(k) = &last_key
        {
            let v = map.entry(k.clone()).or_insert_with(String::new);
            if !v.is_empty() {
                v.push('\u{1f}');
            }
            v.push_str(unquote(item.trim()));
            continue;
        }
        let Some((k, v)) = line.split_once(':') else { continue };
        let (k, v) = (k.trim().to_lowercase(), v.trim());
        last_key = Some(k.clone());
        let value = if let Some(list) = v.strip_prefix('[').and_then(|v| v.strip_suffix(']')) {
            list.split(',').map(|x| unquote(x.trim())).filter(|x| !x.is_empty()).collect::<Vec<_>>().join("\u{1f}")
        } else {
            unquote(v).to_string()
        };
        map.insert(k, value);
    }
    (map, body)
}

fn unquote(s: &str) -> &str {
    s.strip_prefix('"').and_then(|x| x.strip_suffix('"'))
        .or_else(|| s.strip_prefix('\'').and_then(|x| x.strip_suffix('\'')))
        .unwrap_or(s)
}

/// The first YYYY-MM-DD in a string (a file name, usually).
fn find_date(s: &str) -> Option<String> {
    let b = s.as_bytes();
    (0..b.len().saturating_sub(9)).find_map(|i| {
        let w = &s[i..i + 10];
        let ok = w.as_bytes().iter().enumerate().all(|(j, c)| if j == 4 || j == 7 { *c == b'-' } else { c.is_ascii_digit() });
        (ok && w.parse::<jiff::civil::Date>().is_ok()).then(|| w.to_string())
    })
}

fn read_markdown(docs: &[&Doc]) -> Result<Read_> {
    let mut entries = Vec::new();
    let mut images = 0;
    let mut undated = 0;
    for d in docs {
        let src = text(&d.bytes);
        let (fm, body) = front_matter(&src);
        let date = fm
            .get("date")
            .and_then(|v| find_date(v))
            .or_else(|| find_date(&d.name));
        let Some(date) = date else {
            undated += 1;
            continue;
        };
        let time = fm.get("time").and_then(|t| clock_24h(t)).or_else(|| {
            // "2026-10-03T08:30" / "2026-10-03 08:30" in the date field
            fm.get("date").and_then(|v| v.get(11..16)).and_then(clock_24h)
        });
        let parsed = markdown::from_markdown(body);
        images += parsed.skipped_images;
        let tags = fm
            .get("tags")
            .map(|t| t.split(['\u{1f}', ',']).map(|x| x.trim().trim_start_matches('#')).filter(|x| !x.is_empty()).map(|x| (x.to_string(), None)).collect())
            .unwrap_or_default();
        let id = fm.get("id").filter(|v| uuid::Uuid::parse_str(v).is_ok()).cloned();
        entries.push(Incoming {
            reference: match &id {
                Some(id) => format!("librelog:{id}"),
                None => format!("md:{}", d.name),
            },
            id,
            journal_id: None,
            end_date: fm.get("end_date").or_else(|| fm.get("end")).and_then(|v| find_date(v)).filter(|e| *e > date),
            date,
            time,
            tz: None,
            title: fm.get("title").filter(|t| !t.is_empty()).cloned(),
            blocks: parsed.blocks,
            rating: fm.get("rating").and_then(|r| r.parse().ok()),
            favorite: fm.get("favorite").or_else(|| fm.get("starred")).is_some_and(|v| v == "true"),
            tags,
            created_at: None,
        });
    }
    let mut warnings = Vec::new();
    if undated > 0 {
        warnings.push(format!(
            "{undated} file{} had no date in the front matter or file name and will be skipped.",
            if undated == 1 { "" } else { "s" }
        ));
    }
    Ok(Read_ { source: ImportSource::Markdown, entries, journals: vec![], skipped_images: images, warnings })
}

// --- Previewing and committing ----------------------------------------------------

fn already_in(conn: &Connection, e: &Incoming) -> Result<bool> {
    let by_ref: bool = conn.query_row("select exists (select 1 from entries where import_ref = ?1)", [&e.reference], |r| r.get(0))?;
    if by_ref {
        return Ok(true);
    }
    match &e.id {
        Some(id) => Ok(conn.query_row("select exists (select 1 from entries where id = ?1)", [id], |r| r.get(0))?),
        None => Ok(false),
    }
}

pub fn preview(conn: &Connection, path: &str) -> Result<ImportPreview> {
    let r = read_any(path)?;
    let mut already = 0;
    for e in &r.entries {
        already += already_in(conn, e)? as u32;
    }
    let tags: HashSet<String> = r.entries.iter().flat_map(|e| e.tags.iter().map(|(t, _)| t.to_lowercase())).collect();
    let mut dates: Vec<&str> = r.entries.iter().map(|e| e.date.as_str()).collect();
    dates.sort_unstable();
    let mut warnings = r.warnings;
    if r.skipped_images > 0 {
        warnings.push(format!(
            "{} image{} won't be imported: Librelog imports text only.",
            r.skipped_images,
            if r.skipped_images == 1 { "" } else { "s" }
        ));
    }
    Ok(ImportPreview {
        source: r.source,
        entries: r.entries.len() as u32,
        already_imported: already,
        tags: tags.len() as u32,
        first_date: dates.first().map(|d| d.to_string()),
        last_date: dates.last().map(|d| d.to_string()),
        skipped_images: r.skipped_images,
        warnings,
    })
}

pub fn import(tx: &Transaction, clock: &mut Clock, device: &str, path: &str, into_journal: Option<&str>) -> Result<ImportResult> {
    let r = read_any(path)?;

    // Journals from a Librelog export come across with their ids.
    for j in &r.journals {
        let exists: bool = tx.query_row("select exists (select 1 from journals where id = ?1)", [&j.id], |r| r.get(0))?;
        if !exists {
            changes::write(tx, clock, device, "journals", &j.id, fields(json!({ "name": j.name, "color": j.color, "is_default": false, "sort": 1 })))?;
        }
    }

    let mut tag_ids: HashMap<String, String> = HashMap::new();
    let (mut imported, mut skipped) = (0, 0);
    for e in &r.entries {
        if already_in(tx, e)? {
            skipped += 1;
            continue;
        }
        let id = e.id.clone().unwrap_or_else(new_id);
        let journal = into_journal
            .map(str::to_string)
            .or_else(|| e.journal_id.clone())
            .filter(|j| {
                tx.query_row("select exists (select 1 from journals where id = ?1)", [j], |r| r.get::<_, bool>(0))
                    .unwrap_or(false)
            })
            .unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        changes::write(
            tx,
            clock,
            device,
            "entries",
            &id,
            fields(json!({
                "journal_id": journal,
                "date": e.date,
                "time": e.time,
                "end_date": e.end_date,
                "tz": e.tz,
                "title": e.title,
                "body": doc::to_json(&e.blocks),
                "rating": e.rating.map(|r| (r.clamp(0.0, 5.0) * 2.0).round() / 2.0),
                "is_favorite": e.favorite,
                "created_at": e.created_at.clone().unwrap_or_else(now_rfc3339),
                "import_ref": e.reference,
                "deleted_at": null,
            })),
        )?;
        for (name, value) in &e.tags {
            let tag_id = match tag_ids.get(&name.to_lowercase()) {
                Some(t) => t.clone(),
                None => {
                    let t = ensure_tag(tx, clock, device, name)?;
                    tag_ids.insert(name.to_lowercase(), t.clone());
                    t
                }
            };
            changes::write(
                tx,
                clock,
                device,
                "entry_tags",
                &format!("{id}:{tag_id}"),
                fields(json!({ "entry_id": id, "tag_id": tag_id, "value": value, "deleted_at": null })),
            )?;
        }
        imported += 1;
    }
    Ok(ImportResult { source: r.source, imported, skipped })
}

fn ensure_tag(tx: &Transaction, clock: &mut Clock, device: &str, name: &str) -> Result<String> {
    let existing: Option<String> = tx
        .query_row("select id from tags where lower(name) = lower(?1) and deleted_at is null", [name], |r| r.get(0))
        .optional()?;
    if let Some(id) = existing {
        return Ok(id);
    }
    let id = name_id("tag", name);
    changes::write(tx, clock, device, "tags", &id, fields(json!({ "name": name.trim(), "is_tracker": false, "deleted_at": null })))?;
    Ok(id)
}

fn fields(v: Value) -> BTreeMap<String, Value> {
    serde_json::from_value(v).expect("object literal")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clock_formats() {
        assert_eq!(clock_24h("8:05 PM").as_deref(), Some("20:05"));
        assert_eq!(clock_24h("12:30 AM").as_deref(), Some("00:30"));
        assert_eq!(clock_24h("12:30 pm").as_deref(), Some("12:30"));
        assert_eq!(clock_24h("07:15").as_deref(), Some("07:15"));
        assert_eq!(clock_24h("25:00"), None);
        assert_eq!(clock_24h(""), None);
    }

    #[test]
    fn front_matter_lists_and_quotes() {
        let (fm, body) = front_matter("---\ndate: 2026-10-03\ntitle: \"A: day\"\ntags: [run, \"canal walk\"]\nalso:\n  - one\n  - two\n---\n\nBody");
        assert_eq!(fm["date"], "2026-10-03");
        assert_eq!(fm["title"], "A: day");
        assert_eq!(fm["tags"], "run\u{1f}canal walk");
        assert_eq!(fm["also"], "one\u{1f}two");
        assert_eq!(body, "Body");
    }

    #[test]
    fn dates_from_file_names() {
        assert_eq!(find_date("Journal/2025/2025-10-03 0830 - Walk.md").as_deref(), Some("2025-10-03"));
        assert_eq!(find_date("notes/2025-13-03.md"), None);
        assert_eq!(find_date("readme.md"), None);
    }

    #[test]
    fn daylio_html_notes() {
        assert_eq!(strip_html("Line one<br>Line two<br/>Fish &amp; chips"), "Line one\nLine two\nFish & chips");
    }
}
