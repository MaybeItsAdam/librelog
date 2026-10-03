//! Fills a journal database with sample entries, for looking at the apps
//! with something in them. Dev only; never shipped.
//!
//!   cargo run --example seed -- <path to journal.sqlite> [today YYYY-MM-DD]

use librelog_core::doc::{Block, BlockKind};
use librelog_core::Store;

fn para(text: &str) -> Vec<Block> {
    vec![Block { kind: BlockKind::Paragraph, text: text.into(), marks: vec![], indent: 0 }]
}

fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("usage: seed <journal.sqlite> [today]");
    let today = args.next().unwrap_or_else(|| "2026-10-03".into());
    let fmt = time::macros::format_description!("[year]-[month]-[day]");
    let now = time::Date::parse(&today, &fmt).expect("today must be YYYY-MM-DD");
    let day = |n: i64| (now - time::Duration::days(n)).format(&fmt).unwrap();
    let years_ago = |n: i32| now.replace_year(now.year() - n).unwrap_or(now).format(&fmt).unwrap();

    let store = Store::open(path.clone()).expect("open");
    let add = |date: &str, time: &str, title: Option<&str>, text: &str, rating: Option<f64>, tags: &[&str]| {
        let e = store.create_entry(None, date.into(), Some(time.into())).unwrap();
        store.save_body(e.id.clone(), para(text)).unwrap();
        if let Some(t) = title {
            store.set_title(e.id.clone(), Some(t.into())).unwrap();
        }
        store.set_rating(e.id.clone(), rating).unwrap();
        for t in tags {
            let tag = store.tag_named((*t).into()).unwrap();
            store.tag_entry(e.id.clone(), tag.id, None).unwrap();
        }
    };

    // Earlier days, newest first in the journal.
    add(&day(1), "21:40", Some("Long walk"), "Walked the canal to Camden and back. Cold but clear; the light on the water at five was lovely.", Some(4.0), &["Walk"]);
    add(&day(1), "08:15", None, "Slow start. Coffee, read for an hour, then answered the backlog of email.", Some(3.0), &[]);
    add(&day(2), "19:05", Some("Dinner with Sam"), "Tried the new Georgian place. Khachapuri was the highlight. Talked about moving flats.", Some(5.0), &["Friends"]);
    add(&day(4), "07:30", None, "Ran 5k before work. Legs heavy but it cleared my head for the planning meeting.", Some(4.0), &["Run"]);
    add(&day(6), "22:10", Some("Rain"), "Stayed in all day. Finished the puzzle and cooked a big pot of soup for the week.", Some(3.5), &[]);

    // The same date in earlier years, for On This Day.
    add(&years_ago(1), "18:00", Some("A year ago"), "First week in the new job. Everyone friendly; still learning where everything is.", Some(4.0), &[]);
    add(&years_ago(3), "12:30", None, "Picnic in the park with the whole family. Too much food, as usual.", Some(5.0), &["Family"]);

    println!("seeded {path}");
}
