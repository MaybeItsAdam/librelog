//! Reading Markdown into the block document, and writing the document as
//! HTML. Markdown is what other journal apps export (Day One, Obsidian
//! notes, our own Markdown export), so the reader is forgiving and handles
//! the common subset journals actually use.
//!
//! Every non-empty line becomes its own block: in journal apps a line break
//! is a new paragraph, not a soft wrap.

use crate::doc::{Block, BlockKind, Mark, MarkKind, render_marked};

/// What reading a Markdown body produced.
pub struct Parsed {
    pub blocks: Vec<Block>,
    /// Inline images, which text-only import leaves out.
    pub skipped_images: u32,
}

pub fn from_markdown(src: &str) -> Parsed {
    let mut blocks = Vec::new();
    let mut skipped_images = 0;
    let mut in_code = false;

    for raw in src.lines() {
        let line = raw.trim_end();
        let trimmed = line.trim_start();

        if trimmed.starts_with("```") || trimmed.starts_with("~~~") {
            in_code = !in_code;
            continue;
        }
        if in_code {
            blocks.push(block(BlockKind::Code, line.to_string(), vec![], 0));
            continue;
        }
        if trimmed.is_empty() {
            continue;
        }

        // An image on its own line is dropped (and counted).
        if is_image_line(trimmed) {
            skipped_images += 1;
            continue;
        }

        if matches!(trimmed, "---" | "***" | "___") {
            blocks.push(block(BlockKind::Rule, String::new(), vec![], 0));
            continue;
        }

        let indent = indent_level(line);
        let (kind, rest) = line_kind(trimmed);
        let (text, marks, images) = parse_inline(rest);
        skipped_images += images;
        if text.trim().is_empty() && !matches!(kind, BlockKind::Check { .. }) {
            continue;
        }
        let list = matches!(kind, BlockKind::Bullet | BlockKind::Numbered | BlockKind::Check { .. });
        blocks.push(block(kind, text, marks, if list { indent } else { 0 }));
    }
    Parsed { blocks, skipped_images }
}

fn block(kind: BlockKind, text: String, marks: Vec<Mark>, indent: u8) -> Block {
    Block { kind, text, marks, indent }
}

fn is_image_line(s: &str) -> bool {
    s.starts_with("![") && s.ends_with(')') && s.contains("](")
}

/// Two spaces or one tab per nesting level, up to six.
fn indent_level(line: &str) -> u8 {
    let mut spaces = 0;
    for c in line.chars() {
        match c {
            ' ' => spaces += 1,
            '\t' => spaces += 2,
            _ => break,
        }
    }
    (spaces / 2).min(6) as u8
}

/// The block kind a line's prefix stands for, and the rest of the line.
fn line_kind(s: &str) -> (BlockKind, &str) {
    let hashes = s.chars().take_while(|&c| c == '#').count();
    if (1..=6).contains(&hashes) && s[hashes..].starts_with(' ') {
        return (BlockKind::Heading { level: hashes.min(3) as u8 }, s[hashes..].trim_start());
    }
    for (prefix, checked) in [("- [ ] ", false), ("- [x] ", true), ("- [X] ", true), ("* [ ] ", false), ("* [x] ", true)] {
        if let Some(rest) = s.strip_prefix(prefix) {
            return (BlockKind::Check { checked }, rest);
        }
    }
    for prefix in ["- ", "* ", "+ ", "• "] {
        if let Some(rest) = s.strip_prefix(prefix) {
            return (BlockKind::Bullet, rest);
        }
    }
    let digits = s.chars().take_while(char::is_ascii_digit).count();
    if digits > 0 && (s[digits..].starts_with(". ") || s[digits..].starts_with(") ")) {
        return (BlockKind::Numbered, &s[digits + 2..]);
    }
    if let Some(rest) = s.strip_prefix('>') {
        return (BlockKind::Quote, rest.trim_start());
    }
    (BlockKind::Paragraph, s)
}

/// Inline Markdown to text plus marks (UTF-16 offsets). Handles `**bold**`,
/// `__bold__`, `*italic*`, `_italic_`, `~~strike~~`, `==highlight==`,
/// `` `code` ``, `[text](url)`, `[[wiki|label]]`, `<u>…</u>` and backslash
/// escapes. A delimiter with no closing partner on the line stays literal.
/// Returns the number of inline images skipped too.
pub fn parse_inline(src: &str) -> (String, Vec<Mark>, u32) {
    let mut out = String::new();
    let mut len16: u32 = 0;
    let mut marks: Vec<Mark> = Vec::new();
    let mut open: Vec<(MarkKind, &'static str, u32)> = Vec::new();
    let mut images = 0;
    let mut i = 0;

    let push = |out: &mut String, len16: &mut u32, s: &str| {
        out.push_str(s);
        *len16 += s.encode_utf16().count() as u32;
    };

    while i < src.len() {
        let rest = &src[i..];

        // Backslash escapes: \* \_ \# \. \- …
        if let Some(after) = rest.strip_prefix('\\')
            && let Some(c) = after.chars().next()
            && c.is_ascii_punctuation()
        {
            push(&mut out, &mut len16, &c.to_string());
            i += 1 + c.len_utf8();
            continue;
        }

        // Inline code: everything up to the closing backtick is literal.
        if let Some(after) = rest.strip_prefix('`')
            && let Some(end) = after.find('`')
        {
            let start = len16;
            push(&mut out, &mut len16, &after[..end]);
            if len16 > start {
                marks.push(Mark { start, end: len16, kind: MarkKind::Code });
            }
            i += 1 + end + 1;
            continue;
        }

        // Inline images are skipped.
        if rest.starts_with("![")
            && let Some(close) = rest.find("](")
            && let Some(paren) = rest[close..].find(')')
        {
            images += 1;
            i += close + paren + 1;
            continue;
        }

        // [[wiki link]] or [[target|label]]: keep the label as plain text.
        if let Some(after) = rest.strip_prefix("[[")
            && let Some(end) = after.find("]]")
        {
            let inner = &after[..end];
            let label = inner.rsplit_once('|').map_or(inner, |(_, l)| l);
            push(&mut out, &mut len16, label);
            i += 2 + end + 2;
            continue;
        }

        // [text](url)
        if let Some(after) = rest.strip_prefix('[')
            && let Some(close) = after.find("](")
            && let Some(paren) = after[close + 2..].find(')')
        {
            let text = &after[..close];
            let url = &after[close + 2..close + 2 + paren];
            let start = len16;
            push(&mut out, &mut len16, text);
            if len16 > start && !url.is_empty() {
                marks.push(Mark { start, end: len16, kind: MarkKind::Link { url: url.to_string() } });
            }
            i += 1 + close + 2 + paren + 1;
            continue;
        }

        if let Some(after) = rest.strip_prefix("<u>")
            && after.contains("</u>")
        {
            open.push((MarkKind::Underline, "</u>", len16));
            i += 3;
            continue;
        }
        if rest.starts_with("</u>")
            && let Some(pos) = open.iter().rposition(|(k, _, _)| *k == MarkKind::Underline)
        {
            let (kind, _, start) = open.remove(pos);
            if len16 > start {
                marks.push(Mark { start, end: len16, kind });
            }
            i += 4;
            continue;
        }

        // Paired delimiters, longest first so `**` isn't read as two `*`.
        let mut matched = false;
        for (delim, kind) in [
            ("**", MarkKind::Bold),
            ("__", MarkKind::Bold),
            ("~~", MarkKind::Strike),
            ("==", MarkKind::Highlight),
            ("*", MarkKind::Italic),
            ("_", MarkKind::Italic),
        ] {
            if !rest.starts_with(delim) {
                continue;
            }
            // Close the innermost open mark of this delimiter, if any.
            if let Some(pos) = open.iter().rposition(|(_, d, _)| *d == delim) {
                let (kind, _, start) = open.remove(pos);
                if len16 > start {
                    marks.push(Mark { start, end: len16, kind });
                }
                i += delim.len();
                matched = true;
                break;
            }
            // `_` inside a word (snake_case) is just an underscore.
            let prev_word = out.chars().last().is_some_and(char::is_alphanumeric);
            let next = rest[delim.len()..].chars().next();
            let opens = next.is_some_and(|c| !c.is_whitespace())
                && !(delim.starts_with('_') && prev_word)
                && rest[delim.len()..].contains(delim);
            if opens {
                open.push((kind, delim, len16));
                i += delim.len();
                matched = true;
            }
            break;
        }
        if matched {
            continue;
        }

        let c = rest.chars().next().expect("non-empty rest");
        push(&mut out, &mut len16, &c.to_string());
        i += c.len_utf8();
    }

    marks.sort_by_key(|m| (m.start, std::cmp::Reverse(m.end)));
    (out, marks, images)
}

// --- HTML ----------------------------------------------------------------------

pub fn escape_html(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            c => out.push(c),
        }
    }
    out
}

/// The body as HTML. Lists are wrapped in `<ul>`/`<ol>`; nesting depth
/// becomes a left margin so the output stays flat and valid.
pub fn to_html(blocks: &[Block], entry_title: &dyn Fn(&str) -> Option<String>) -> String {
    let mut out = String::new();
    let mut list: Option<&'static str> = None;
    for b in blocks {
        let want = match b.kind {
            BlockKind::Bullet | BlockKind::Check { .. } => Some("ul"),
            BlockKind::Numbered => Some("ol"),
            _ => None,
        };
        if list != want {
            if let Some(l) = list {
                out.push_str(&format!("</{l}>\n"));
            }
            if let Some(l) = want {
                out.push_str(&format!("<{l}>\n"));
            }
            list = want;
        }
        let inline = inline_html(&b.text, &b.marks, entry_title);
        let pad = if b.indent > 0 { format!(" style=\"margin-left:{}em\"", b.indent as f32 * 1.5) } else { String::new() };
        let line = match &b.kind {
            BlockKind::Paragraph => format!("<p>{inline}</p>"),
            BlockKind::Heading { level } => {
                let h = (*level).clamp(1, 3) + 1; // the entry title is <h2>
                format!("<h{h}>{inline}</h{h}>")
            }
            BlockKind::Bullet | BlockKind::Numbered => format!("<li{pad}>{inline}</li>"),
            BlockKind::Check { checked } => format!(
                "<li class=\"check\"{pad}><input type=\"checkbox\" disabled{}> {inline}</li>",
                if *checked { " checked" } else { "" }
            ),
            BlockKind::Quote => format!("<blockquote>{inline}</blockquote>"),
            BlockKind::Code => format!("<pre><code>{}</code></pre>", escape_html(&b.text)),
            BlockKind::Rule => "<hr>".to_string(),
            BlockKind::Image { .. } => format!("<p class=\"caption\">{inline}</p>"),
        };
        out.push_str(&line);
        out.push('\n');
    }
    if let Some(l) = list {
        out.push_str(&format!("</{l}>\n"));
    }
    out
}

fn inline_html(text: &str, marks: &[Mark], entry_title: &dyn Fn(&str) -> Option<String>) -> String {
    render_marked(
        text,
        marks,
        &|kind, open| match (kind, open) {
            (MarkKind::Bold, true) => "<strong>".into(),
            (MarkKind::Bold, false) => "</strong>".into(),
            (MarkKind::Italic, true) => "<em>".into(),
            (MarkKind::Italic, false) => "</em>".into(),
            (MarkKind::Underline, true) => "<u>".into(),
            (MarkKind::Underline, false) => "</u>".into(),
            (MarkKind::Strike, true) => "<s>".into(),
            (MarkKind::Strike, false) => "</s>".into(),
            (MarkKind::Highlight, true) => "<mark>".into(),
            (MarkKind::Highlight, false) => "</mark>".into(),
            (MarkKind::Code, true) => "<code>".into(),
            (MarkKind::Code, false) => "</code>".into(),
            (MarkKind::Link { url }, true) => format!("<a href=\"{}\">", escape_html(url)),
            (MarkKind::Link { .. }, false) => "</a>".into(),
            (MarkKind::EntryLink { entry_id }, true) => {
                let title = entry_title(entry_id).unwrap_or_default();
                format!("<a href=\"#e-{}\" title=\"{}\">", escape_html(entry_id), escape_html(&title))
            }
            (MarkKind::EntryLink { .. }, false) => "</a>".into(),
        },
        &escape_html,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::doc::to_markdown;

    fn kinds(p: &Parsed) -> Vec<BlockKind> {
        p.blocks.iter().map(|b| b.kind.clone()).collect()
    }

    #[test]
    fn block_kinds_and_indentation() {
        let p = from_markdown(
            "# Title\n\nPara one\nPara two\n- a\n  - nested\n1. first\n2) second\n- [ ] todo\n- [x] done\n> quote\n---\n```\nlet x = 1;\n```\n",
        );
        assert_eq!(
            kinds(&p),
            vec![
                BlockKind::Heading { level: 1 },
                BlockKind::Paragraph,
                BlockKind::Paragraph,
                BlockKind::Bullet,
                BlockKind::Bullet,
                BlockKind::Numbered,
                BlockKind::Numbered,
                BlockKind::Check { checked: false },
                BlockKind::Check { checked: true },
                BlockKind::Quote,
                BlockKind::Rule,
                BlockKind::Code,
            ]
        );
        assert_eq!(p.blocks[4].indent, 1);
        assert_eq!(p.blocks[11].text, "let x = 1;");
    }

    #[test]
    fn inline_marks_use_utf16_offsets() {
        let (text, marks, _) = parse_inline("😀 **bold** and *it* `c` [link](https://x.y)");
        assert_eq!(text, "😀 bold and it c link");
        // 😀 is two UTF-16 units, so "bold" is 3..7.
        assert_eq!(marks[0], Mark { start: 3, end: 7, kind: MarkKind::Bold });
        assert!(marks.contains(&Mark { start: 12, end: 14, kind: MarkKind::Italic }));
        assert!(marks.contains(&Mark { start: 15, end: 16, kind: MarkKind::Code }));
        assert!(marks.iter().any(|m| matches!(&m.kind, MarkKind::Link { url } if url == "https://x.y")));
    }

    #[test]
    fn unclosed_delimiters_escapes_and_snake_case_stay_literal() {
        assert_eq!(parse_inline("2 * 3 = 6").0, "2 * 3 = 6");
        assert_eq!(parse_inline("snake_case_name").0, "snake_case_name");
        assert_eq!(parse_inline(r"1\. not a list \*literal\*").0, "1. not a list *literal*");
        assert!(parse_inline("snake_case_name").1.is_empty());
    }

    #[test]
    fn images_are_skipped_and_counted() {
        let p = from_markdown("Before\n![](dayone-moment://ABC)\nText ![x](a.png) after");
        assert_eq!(p.skipped_images, 2);
        assert_eq!(p.blocks.len(), 2);
        assert_eq!(p.blocks[1].text, "Text  after");
    }

    #[test]
    fn wiki_links_keep_their_label() {
        assert_eq!(parse_inline("see [[2026-10-02|yesterday]] and [[Notes]]").0, "see yesterday and Notes");
    }

    #[test]
    fn our_own_markdown_round_trips() {
        let doc = vec![
            Block { kind: BlockKind::Heading { level: 2 }, text: "Morning".into(), marks: vec![], indent: 0 },
            Block {
                kind: BlockKind::Paragraph,
                text: "Ran 5k by the canal".into(),
                marks: vec![
                    Mark { start: 0, end: 3, kind: MarkKind::Bold },
                    Mark { start: 10, end: 19, kind: MarkKind::Highlight },
                ],
                indent: 0,
            },
            Block { kind: BlockKind::Bullet, text: "eggs".into(), marks: vec![], indent: 0 },
            Block { kind: BlockKind::Bullet, text: "nested".into(), marks: vec![], indent: 1 },
            Block { kind: BlockKind::Check { checked: true }, text: "done".into(), marks: vec![], indent: 0 },
            Block { kind: BlockKind::Quote, text: "a quote".into(), marks: vec![], indent: 0 },
        ];
        let md = to_markdown(&doc, &|_| None);
        assert_eq!(from_markdown(&md).blocks, doc, "{md}");
    }

    #[test]
    fn html_escapes_and_wraps_lists() {
        let doc = vec![
            Block { kind: BlockKind::Paragraph, text: "a < b & \"c\"".into(), marks: vec![], indent: 0 },
            Block { kind: BlockKind::Bullet, text: "one".into(), marks: vec![Mark { start: 0, end: 3, kind: MarkKind::Bold }], indent: 0 },
            Block { kind: BlockKind::Bullet, text: "two".into(), marks: vec![], indent: 0 },
            Block {
                kind: BlockKind::Paragraph,
                text: "x".into(),
                marks: vec![Mark { start: 0, end: 1, kind: MarkKind::Link { url: "javascript:\"alert(1)\"".into() } }],
                indent: 0,
            },
        ];
        let html = to_html(&doc, &|_| None);
        assert!(html.contains("<p>a &lt; b &amp; &quot;c&quot;</p>"), "{html}");
        assert!(html.contains("<ul>\n<li><strong>one</strong></li>\n<li>two</li>\n</ul>"), "{html}");
        assert!(html.contains("href=\"javascript:&quot;alert(1)&quot;\""), "{html}");
    }
}
