//! The search box's query language (SPEC.md §6.11), turned into SQL.
//!
//! `walk park tag:run rating:>=4 before:2026-01-01 -rain "long way"`
//!
//! Free words become an FTS5 prefix match; operators become `where` clauses.

use rusqlite::types::Value as Sql;

#[derive(Debug, Default, PartialEq)]
pub struct Query {
    /// FTS5 MATCH expression, if there were any free-text terms.
    pub fts: Option<String>,
    /// Extra `and ...` clauses over `entries e`, with their parameters.
    pub clauses: Vec<String>,
    pub params: Vec<Sql>,
}

pub fn parse(input: &str) -> Query {
    let mut q = Query::default();
    let mut positive: Vec<String> = Vec::new();
    let mut negative: Vec<String> = Vec::new();

    for token in tokens(input) {
        let (negated, token) = match token.strip_prefix('-') {
            Some(rest) if !rest.is_empty() => (true, rest.to_string()),
            _ => (false, token),
        };

        if let Some(phrase) = token.strip_prefix('"').map(|t| t.trim_end_matches('"')) {
            let term = fts_phrase(phrase);
            if !term.is_empty() {
                if negated { negative.push(term) } else { positive.push(term) }
            }
            continue;
        }

        if let Some((op, arg)) = token.split_once(':')
            && !arg.is_empty()
            && filter(&mut q, &op.to_lowercase(), arg, negated)
        {
            continue;
        }

        let term = fts_prefix(&token);
        if !term.is_empty() {
            if negated { negative.push(term) } else { positive.push(term) }
        }
    }

    // FTS5 can't evaluate a NOT on its own, so negative-only text queries
    // become a NOT IN over a positive match.
    match (positive.is_empty(), negative.is_empty()) {
        (false, _) => {
            let mut expr = positive.join(" ");
            for n in &negative {
                expr.push_str(" NOT ");
                expr.push_str(n);
            }
            q.fts = Some(expr);
        }
        (true, false) => {
            q.clauses.push(
                "e.rowid not in (select rowid from entries_fts where entries_fts match ?)".into(),
            );
            q.params.push(Sql::Text(negative.join(" OR ")));
        }
        (true, true) => {}
    }
    q
}

/// Applies `op:arg`. Returns false for unknown operators, so `re:thing` is
/// searched as text rather than silently dropped.
fn filter(q: &mut Query, op: &str, arg: &str, negated: bool) -> bool {
    let not = if negated { "not " } else { "" };
    let arg_lc = arg.to_lowercase();
    match op {
        "tag" | "t" => {
            q.clauses.push(format!(
                "{not}exists (select 1 from entry_tags et join tags t on t.id = et.tag_id
                  where et.entry_id = e.id and et.deleted_at is null and t.deleted_at is null
                    and lower(t.name) = ?)"
            ));
            q.params.push(Sql::Text(arg_lc));
        }
        "person" | "with" => {
            q.clauses.push(format!(
                "{not}exists (select 1 from entry_people ep join people p on p.id = ep.person_id
                  where ep.entry_id = e.id and ep.deleted_at is null and lower(p.name) like ? || '%')"
            ));
            q.params.push(Sql::Text(arg_lc));
        }
        "in" | "journal" => {
            q.clauses.push(format!(
                "e.journal_id {not}in (select id from journals where lower(name) = ? and deleted_at is null)"
            ));
            q.params.push(Sql::Text(arg_lc));
        }
        "rating" => {
            let (cmp, num) = split_comparison(arg);
            let Ok(n) = num.parse::<f64>() else { return false };
            q.clauses.push(format!("{not}(e.rating {cmp} ?)"));
            q.params.push(Sql::Real(n));
        }
        "before" | "after" | "on" | "date" => {
            if !looks_like_date(arg) {
                return false;
            }
            let (cmp, val) = match op {
                "before" => ("<", arg.to_string()),
                "after" => (">", arg.to_string()),
                // on:2026 / on:2026-10 / on:2026-10-03 all work as prefixes
                _ => ("like", format!("{arg}%")),
            };
            q.clauses.push(format!("{not}(e.date {cmp} ?)"));
            q.params.push(Sql::Text(val));
        }
        "has" => {
            let clause = match arg_lc.as_str() {
                "photo" | "photos" | "video" | "videos" | "audio" | "file" | "files" | "drawing" => {
                    let kind = arg_lc.trim_end_matches('s').to_string();
                    q.params.push(Sql::Text(kind));
                    "exists (select 1 from attachments a where a.entry_id = e.id and a.deleted_at is null and a.kind = ?)"
                }
                "location" | "place" => "e.place_id is not null",
                "rating" => "e.rating is not null",
                "title" => "coalesce(e.title, '') <> ''",
                _ => return false,
            };
            q.clauses.push(format!("{not}({clause})"));
        }
        "is" => {
            let clause = match arg_lc.as_str() {
                "favorite" | "favourite" | "fav" | "starred" => "e.is_favorite = 1",
                "conflict" => "exists (select 1 from revisions r where r.entry_id = e.id and r.is_conflict = 1)",
                _ => return false,
            };
            q.clauses.push(format!("{not}({clause})"));
        }
        _ => return false,
    }
    true
}

fn split_comparison(arg: &str) -> (&'static str, &str) {
    for (prefix, cmp) in [(">=", ">="), ("<=", "<="), (">", ">"), ("<", "<"), ("=", "=")] {
        if let Some(rest) = arg.strip_prefix(prefix) {
            return (cmp, rest);
        }
    }
    ("=", arg)
}

fn looks_like_date(s: &str) -> bool {
    let digits_and_dashes = s.chars().all(|c| c.is_ascii_digit() || c == '-');
    digits_and_dashes && s.len() >= 4 && s.chars().take(4).all(|c| c.is_ascii_digit())
}

/// Splits on whitespace, keeping `"quoted phrases"` (and `-"negated ones"`)
/// together.
fn tokens(input: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut quoted = false;
    for c in input.chars() {
        match c {
            '"' => {
                cur.push(c);
                if quoted {
                    out.push(std::mem::take(&mut cur));
                }
                quoted = !quoted;
            }
            c if c.is_whitespace() && !quoted => {
                if !cur.is_empty() {
                    out.push(std::mem::take(&mut cur));
                }
            }
            c => cur.push(c),
        }
    }
    if !cur.is_empty() {
        out.push(cur);
    }
    out
}

/// A free word as an FTS5 prefix term. Everything but letters and digits is
/// dropped so no user input can inject FTS5 syntax.
fn fts_prefix(word: &str) -> String {
    let clean: String = word.chars().filter(|c| c.is_alphanumeric()).collect();
    if clean.is_empty() { String::new() } else { format!("\"{clean}\"*") }
}

fn fts_phrase(phrase: &str) -> String {
    let words: Vec<String> = phrase
        .split_whitespace()
        .map(|w| w.chars().filter(|c| c.is_alphanumeric()).collect::<String>())
        .filter(|w| !w.is_empty())
        .collect();
    if words.is_empty() { String::new() } else { format!("\"{}\"", words.join(" ")) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn free_words_become_prefix_terms() {
        let q = parse("walk park");
        assert_eq!(q.fts.as_deref(), Some("\"walk\"* \"park\"*"));
        assert!(q.clauses.is_empty());
    }

    #[test]
    fn phrases_negation_and_operators() {
        let q = parse(r#""long way" -rain tag:Run rating:>=4 before:2026-01-01"#);
        assert_eq!(q.fts.as_deref(), Some("\"long way\" NOT \"rain\"*"));
        assert_eq!(q.clauses.len(), 3);
        assert_eq!(q.params, vec![Sql::Text("run".into()), Sql::Real(4.0), Sql::Text("2026-01-01".into())]);
    }

    #[test]
    fn negated_operators_and_negative_only_text() {
        let q = parse("-tag:work -meeting");
        assert!(q.fts.is_none());
        assert!(q.clauses[0].starts_with("not exists"));
        assert!(q.clauses[1].contains("not in (select rowid from entries_fts"));
    }

    #[test]
    fn unknown_operators_and_bad_values_are_searched_as_text() {
        assert_eq!(parse("re:thing").fts.as_deref(), Some("\"rething\"*"));
        assert_eq!(parse("rating:great").fts.as_deref(), Some("\"ratinggreat\"*"));
    }

    #[test]
    fn fts_syntax_in_user_input_is_neutralised() {
        assert_eq!(parse("a* OR (b) NEAR").fts.as_deref(), Some("\"a\"* \"OR\"* \"b\"* \"NEAR\"*"));
        assert_eq!(parse("\"\"").fts, None);
    }
}
