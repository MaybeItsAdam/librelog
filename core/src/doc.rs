//! The entry body: a flat list of blocks with inline marks (SPEC.md §6.2).
//!
//! Each platform's text engine (TextKit 2, Android `Spannable`, WinUI
//! `RichEditBox`) converts to and from this; the core owns the canonical form
//! and every serialiser, so an entry renders and exports the same everywhere.
//!
//! Mark offsets are **UTF-16 code units**, because that's what NSString,
//! Java/Kotlin strings and .NET strings index by. Converting at the boundary
//! in Rust is cheaper than in three UI layers.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Block {
    pub kind: BlockKind,
    pub text: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub marks: Vec<Mark>,
    /// List nesting depth, 0-based. Ignored for non-list blocks.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub indent: u8,
}

fn is_zero(n: &u8) -> bool {
    *n == 0
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum BlockKind {
    Paragraph,
    Heading { level: u8 },
    Bullet,
    Numbered,
    Check { checked: bool },
    Quote,
    Code,
    Rule,
    /// An attachment shown inline; `text` is its caption.
    Image { attachment_id: String },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Mark {
    /// UTF-16 offsets into the block's text, `start < end`.
    pub start: u32,
    pub end: u32,
    pub kind: MarkKind,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum MarkKind {
    Bold,
    Italic,
    Underline,
    Strike,
    Highlight,
    Code,
    Link { url: String },
    /// An internal link to another entry, made with the `[[` picker.
    EntryLink { entry_id: String },
}

pub fn parse(json: &str) -> Vec<Block> {
    serde_json::from_str(json).unwrap_or_default()
}

pub fn to_json(blocks: &[Block]) -> String {
    serde_json::to_string(blocks).expect("blocks always serialise")
}

/// Plain text for search and previews: one line per block, captions included.
pub fn plain_text(blocks: &[Block]) -> String {
    let mut out = String::new();
    for b in blocks {
        if matches!(b.kind, BlockKind::Rule) {
            continue;
        }
        if !out.is_empty() {
            out.push('\n');
        }
        out.push_str(&b.text);
    }
    out
}

pub fn word_count(blocks: &[Block]) -> u32 {
    blocks
        .iter()
        .filter(|b| !matches!(b.kind, BlockKind::Image { .. } | BlockKind::Rule))
        .map(|b| count_words(&b.text))
        .sum()
}

/// Counts runs of letters/digits, treating each CJK character as a word, which
/// is how most writers and editors count Chinese and Japanese.
pub fn count_words(s: &str) -> u32 {
    let mut n = 0;
    let mut in_word = false;
    for c in s.chars() {
        if is_cjk(c) {
            n += 1;
            in_word = false;
        } else if c.is_alphanumeric() || c == '\'' || c == '’' {
            if !in_word {
                n += 1;
                in_word = true;
            }
        } else {
            in_word = false;
        }
    }
    n
}

fn is_cjk(c: char) -> bool {
    matches!(c as u32,
        0x3040..=0x30ff |   // Hiragana, Katakana
        0x3400..=0x4dbf |   // CJK Extension A
        0x4e00..=0x9fff |   // CJK Unified
        0xac00..=0xd7af |   // Hangul syllables
        0xf900..=0xfaff)    // CJK compatibility
}

/// First non-empty block's text, cut to `max` characters on a word boundary.
pub fn excerpt(blocks: &[Block], max: usize) -> String {
    excerpt_text(&plain_text(blocks), max)
}

/// [`excerpt`] for text that's already plain, such as `entries.body_text`.
pub fn excerpt_text(text: &str, max: usize) -> String {
    let flat: String = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if flat.chars().count() <= max {
        return flat;
    }
    let cut: String = flat.chars().take(max).collect();
    match cut.rfind(' ') {
        Some(i) if i > max / 2 => format!("{}…", &cut[..i]),
        _ => format!("{cut}…"),
    }
}

/// Markdown export (Obsidian-flavoured: `==highlight==`, `[[entry]]` links).
pub fn to_markdown(blocks: &[Block], entry_title: &dyn Fn(&str) -> Option<String>) -> String {
    let mut out = String::new();
    let mut prev: Option<&BlockKind> = None;
    let mut number = 0u32;
    for b in blocks {
        let list = matches!(b.kind, BlockKind::Bullet | BlockKind::Numbered | BlockKind::Check { .. });
        let prev_list = matches!(prev, Some(BlockKind::Bullet | BlockKind::Numbered | BlockKind::Check { .. }));
        if !out.is_empty() {
            out.push_str(if list && prev_list { "\n" } else { "\n\n" });
        }
        number = match (&b.kind, prev) {
            (BlockKind::Numbered, Some(BlockKind::Numbered)) => number + 1,
            (BlockKind::Numbered, _) => 1,
            _ => 0,
        };
        let pad = "  ".repeat(b.indent as usize);
        let inline = render_inline(&b.text, &b.marks, entry_title);
        match &b.kind {
            BlockKind::Paragraph => out.push_str(&inline),
            BlockKind::Heading { level } => {
                out.push_str(&"#".repeat((*level).clamp(1, 6) as usize));
                out.push(' ');
                out.push_str(&inline);
            }
            BlockKind::Bullet => out.push_str(&format!("{pad}- {inline}")),
            BlockKind::Numbered => out.push_str(&format!("{pad}{number}. {inline}")),
            BlockKind::Check { checked } => {
                out.push_str(&format!("{pad}- [{}] {inline}", if *checked { "x" } else { " " }))
            }
            BlockKind::Quote => {
                let quoted: Vec<String> = inline.lines().map(|l| format!("> {l}")).collect();
                out.push_str(&quoted.join("\n"));
            }
            BlockKind::Code => out.push_str(&format!("```\n{}\n```", b.text)),
            BlockKind::Rule => out.push_str("---"),
            BlockKind::Image { attachment_id } => {
                out.push_str(&format!("![{}](attachments/{attachment_id})", b.text))
            }
        }
        prev = Some(&b.kind);
    }
    out
}

fn render_inline(text: &str, marks: &[Mark], entry_title: &dyn Fn(&str) -> Option<String>) -> String {
    if marks.is_empty() {
        return text.to_string();
    }
    // Open/close tokens keyed by UTF-16 offset. Closes sort before opens at the
    // same offset, and inner marks close before outer ones.
    let mut events: Vec<(u32, bool, usize)> = Vec::new(); // (offset, is_open, mark index)
    for (i, m) in marks.iter().enumerate() {
        if m.start < m.end {
            events.push((m.start, true, i));
            events.push((m.end, false, i));
        }
    }
    events.sort_by(|a, b| {
        a.0.cmp(&b.0).then(a.1.cmp(&b.1)).then_with(|| {
            let (ma, mb) = (&marks[a.2], &marks[b.2]);
            if a.1 {
                mb.end.cmp(&ma.end) // open the longer mark first
            } else {
                mb.start.cmp(&ma.start) // close the later-opened mark first
            }
        })
    });

    let units: Vec<u16> = text.encode_utf16().collect();
    let mut out = String::new();
    let mut at = 0u32;
    for (offset, open, i) in events {
        let offset = offset.min(units.len() as u32);
        if offset > at {
            out.push_str(&String::from_utf16_lossy(&units[at as usize..offset as usize]));
            at = offset;
        }
        let m = &marks[i];
        let token = match (&m.kind, open) {
            (MarkKind::Bold, _) => "**".to_string(),
            (MarkKind::Italic, _) => "*".to_string(),
            (MarkKind::Underline, true) => "<u>".to_string(),
            (MarkKind::Underline, false) => "</u>".to_string(),
            (MarkKind::Strike, _) => "~~".to_string(),
            (MarkKind::Highlight, _) => "==".to_string(),
            (MarkKind::Code, _) => "`".to_string(),
            (MarkKind::Link { .. }, true) => "[".to_string(),
            (MarkKind::Link { url }, false) => format!("]({url})"),
            (MarkKind::EntryLink { .. }, true) => "[[".to_string(),
            (MarkKind::EntryLink { entry_id }, false) => match entry_title(entry_id) {
                Some(t) => format!("|{t}]]"),
                None => "]]".to_string(),
            },
        };
        out.push_str(&token);
    }
    if (at as usize) < units.len() {
        out.push_str(&String::from_utf16_lossy(&units[at as usize..]));
    }
    out
}

/// Recognises the Markdown shortcuts typed at the start of a block (SPEC.md
/// §6.2): `# `, `## `, `### `, `- `, `* `, `1. `, `[ ] `, `> `, ` ``` `, `---`.
/// Returns the new kind and how many UTF-16 units of prefix to strip, or
/// `None` if the text doesn't start with a shortcut.
pub fn shortcut(text: &str) -> Option<(BlockKind, u32)> {
    let table: &[(&str, BlockKind)] = &[
        ("### ", BlockKind::Heading { level: 3 }),
        ("## ", BlockKind::Heading { level: 2 }),
        ("# ", BlockKind::Heading { level: 1 }),
        ("- [ ] ", BlockKind::Check { checked: false }),
        ("- [x] ", BlockKind::Check { checked: true }),
        ("[ ] ", BlockKind::Check { checked: false }),
        ("[] ", BlockKind::Check { checked: false }),
        ("- ", BlockKind::Bullet),
        ("* ", BlockKind::Bullet),
        ("1. ", BlockKind::Numbered),
        ("> ", BlockKind::Quote),
        ("```", BlockKind::Code),
    ];
    if text == "---" {
        return Some((BlockKind::Rule, 3));
    }
    table
        .iter()
        .find(|(p, _)| text.starts_with(p))
        .map(|(p, k)| (k.clone(), p.encode_utf16().count() as u32))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(text: &str) -> Block {
        Block { kind: BlockKind::Paragraph, text: text.into(), marks: vec![], indent: 0 }
    }

    #[test]
    fn json_round_trips_and_stays_compact() {
        let blocks = vec![
            Block { kind: BlockKind::Heading { level: 2 }, text: "Morning".into(), marks: vec![], indent: 0 },
            Block {
                kind: BlockKind::Paragraph,
                text: "ran 5k".into(),
                marks: vec![Mark { start: 0, end: 3, kind: MarkKind::Bold }],
                indent: 0,
            },
        ];
        let json = to_json(&blocks);
        assert_eq!(parse(&json), blocks);
        assert!(!json.contains("indent"), "{json}");
    }

    #[test]
    fn bad_json_is_an_empty_document_not_a_crash() {
        assert!(parse("not json").is_empty());
    }

    #[test]
    fn counts_words_in_latin_and_cjk_text() {
        assert_eq!(count_words("It's a fine day — really."), 5);
        assert_eq!(count_words("今日は晴れ"), 5);
        assert_eq!(count_words(""), 0);
    }

    #[test]
    fn excerpt_cuts_on_a_word_boundary() {
        let e = excerpt(&[p("The quick brown fox jumps over the lazy dog")], 20);
        assert_eq!(e, "The quick brown fox…");
    }

    #[test]
    fn markdown_handles_nested_marks_and_utf16_offsets() {
        // "😀 bold" — the emoji is 2 UTF-16 units, so "bold" is 3..7.
        let b = Block {
            kind: BlockKind::Paragraph,
            text: "😀 bold".into(),
            marks: vec![
                Mark { start: 3, end: 7, kind: MarkKind::Bold },
                Mark { start: 3, end: 5, kind: MarkKind::Italic },
            ],
            indent: 0,
        };
        assert_eq!(to_markdown(&[b], &|_| None), "😀 ***bo*ld**");
    }

    #[test]
    fn markdown_numbers_lists_and_renders_entry_links() {
        let blocks = vec![
            Block { kind: BlockKind::Numbered, text: "one".into(), marks: vec![], indent: 0 },
            Block { kind: BlockKind::Numbered, text: "two".into(), marks: vec![], indent: 0 },
            Block {
                kind: BlockKind::Paragraph,
                text: "see yesterday".into(),
                marks: vec![Mark { start: 4, end: 13, kind: MarkKind::EntryLink { entry_id: "e1".into() } }],
                indent: 0,
            },
        ];
        let md = to_markdown(&blocks, &|id| (id == "e1").then(|| "2 Oct".to_string()));
        assert_eq!(md, "1. one\n2. two\n\nsee [[yesterday|2 Oct]]");
    }

    #[test]
    fn shortcuts() {
        assert_eq!(shortcut("## Hi"), Some((BlockKind::Heading { level: 2 }, 3)));
        assert_eq!(shortcut("- [ ] milk"), Some((BlockKind::Check { checked: false }, 6)));
        assert_eq!(shortcut("---"), Some((BlockKind::Rule, 3)));
        assert_eq!(shortcut("plain"), None);
    }
}

// --- Exported to the apps -----------------------------------------------------

/// A Markdown shortcut typed at the start of a block.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Shortcut {
    pub kind: BlockKind,
    /// UTF-16 units of typed prefix to remove.
    pub prefix_len: u32,
}

#[uniffi::export]
pub fn markdown_shortcut(text: String) -> Option<Shortcut> {
    shortcut(&text).map(|(kind, prefix_len)| Shortcut { kind, prefix_len })
}

#[uniffi::export]
pub fn blocks_to_markdown(blocks: Vec<Block>) -> String {
    to_markdown(&blocks, &|_| None)
}

#[uniffi::export]
pub fn blocks_word_count(blocks: Vec<Block>) -> u32 {
    word_count(&blocks)
}
