//! A fictional journal for the store screenshots: a few weeks of entries,
//! photos on some days, kept events and a locked note. Run through
//! scripts/store-demo.py, which renders the photos first. Dev only.
//!
//!   cargo run --example store_demo -- <journal.sqlite> <today YYYY-MM-DD> <photos dir>

use std::path::Path;

use librelog_core::doc::{Block, BlockKind, Mark, MarkKind};
use librelog_core::{NewPhoto, OwnEvent, PreviewChoice, Store};

/// The locked note's password, for opening it in a screenshot.
const LOCK_PASSWORD: &str = "harbour";

/// One line of the body. Line prefixes: `# ` heading, `- ` bullet,
/// `[ ] ` / `[x] ` checklist, `> ` quote. Inline: `**bold**`, `*italic*`,
/// `==highlight==`.
fn block(line: &str) -> Block {
    let (kind, rest) = if let Some(r) = line.strip_prefix("# ") {
        (BlockKind::Heading { level: 2 }, r)
    } else if let Some(r) = line.strip_prefix("- ") {
        (BlockKind::Bullet, r)
    } else if let Some(r) = line.strip_prefix("[ ] ") {
        (BlockKind::Check { checked: false }, r)
    } else if let Some(r) = line.strip_prefix("[x] ") {
        (BlockKind::Check { checked: true }, r)
    } else if let Some(r) = line.strip_prefix("> ") {
        (BlockKind::Quote, r)
    } else {
        (BlockKind::Paragraph, line)
    };
    let (text, marks) = inline(rest);
    Block { kind, text, marks, indent: 0 }
}

/// Strips the inline delimiters, recording each run as a mark in UTF-16 units.
fn inline(src: &str) -> (String, Vec<Mark>) {
    let delims = [("**", MarkKind::Bold), ("==", MarkKind::Highlight), ("*", MarkKind::Italic)];
    let mut text = String::new();
    let mut marks = Vec::new();
    let mut open: Vec<(&str, u32)> = Vec::new();
    let mut rest = src;
    let units = |s: &str| s.encode_utf16().count() as u32;
    'outer: while !rest.is_empty() {
        for (d, kind) in &delims {
            if let Some(r) = rest.strip_prefix(d) {
                if let Some(pos) = open.iter().rposition(|(o, _)| o == d) {
                    let (_, start) = open.remove(pos);
                    marks.push(Mark { start, end: units(&text), kind: kind.clone() });
                } else {
                    open.push((d, units(&text)));
                }
                rest = r;
                continue 'outer;
            }
        }
        let c = rest.chars().next().unwrap();
        text.push(c);
        rest = &rest[c.len_utf8()..];
    }
    (text, marks)
}

fn body(src: &str) -> Vec<Block> {
    src.lines().map(str::trim).filter(|l| !l.is_empty()).map(block).collect()
}

struct Demo {
    store: std::sync::Arc<Store>,
    photos: String,
    sizes: serde_json::Value,
}

impl Demo {
    #[allow(clippy::too_many_arguments)]
    fn entry(&self, date: &str, time: Option<&str>, title: Option<&str>, text: &str, rating: Option<f64>, tags: &[&str], favourite: bool) -> String {
        let s = &self.store;
        let e = s.create_entry(None, date.into(), time.map(Into::into)).unwrap();
        s.save_body(e.id.clone(), body(text)).unwrap();
        s.set_title(e.id.clone(), title.map(Into::into)).unwrap();
        s.set_rating(e.id.clone(), rating).unwrap();
        s.set_favorite(e.id.clone(), favourite).unwrap();
        for t in tags {
            let tag = s.tag_named((*t).into()).unwrap();
            s.tag_entry(e.id.clone(), tag.id, None).unwrap();
        }
        e.id
    }

    /// Keeps a rendered scene on a day, as the apps do after picking a photo.
    /// The core moves the files in, so it gets copies.
    fn photo(&self, date: &str, scene: &str, taken: &str, caption: Option<&str>) -> String {
        let dir = Path::new(&self.photos);
        let tmp = std::env::temp_dir().join(format!("librelog-demo-{scene}-{date}"));
        std::fs::create_dir_all(&tmp).unwrap();
        let orig = tmp.join(format!("{scene}.jpg"));
        let prev = tmp.join(format!("{scene}-1024.jpg"));
        std::fs::copy(dir.join(format!("{scene}.jpg")), &orig).unwrap();
        std::fs::copy(dir.join(format!("{scene}-1024.jpg")), &prev).unwrap();
        let rgba = std::fs::read(dir.join(format!("{scene}.rgba"))).unwrap();
        let size = &self.sizes[scene];
        let n = |k: &str| size[k].as_u64().unwrap() as u32;
        let photo = self
            .store
            .add_day_photo(None, date.into(), NewPhoto {
                path: orig.to_string_lossy().into(),
                mime: "image/jpeg".into(),
                width: n("width"),
                height: n("height"),
                taken_at: Some(format!("{date}T{taken}:00Z")),
                lat: None,
                lng: None,
                preview_path: prev.to_string_lossy().into(),
                hash_rgba: rgba,
                hash_width: n("hash_width"),
                hash_height: n("hash_height"),
                source_ref: None,
                local_asset: None,
            })
            .unwrap();
        if let Some(c) = caption {
            self.store.set_photo_caption(photo.id.clone(), Some(c.into())).unwrap();
        }
        let _ = std::fs::remove_dir_all(&tmp);
        photo.id
    }

    fn event(&self, date: &str, title: &str, start: Option<&str>, end: Option<&str>, place: Option<&str>) {
        let ev = OwnEvent {
            title: title.into(),
            start_time: start.map(Into::into),
            end_time: end.map(Into::into),
            location: place.map(Into::into),
        };
        self.store.add_own_event(None, date.into(), ev).unwrap();
    }
}

fn main() {
    let mut args = std::env::args().skip(1);
    let usage = "usage: store_demo <journal.sqlite> <today YYYY-MM-DD> <photos dir>";
    let path = args.next().expect(usage);
    let today = args.next().expect(usage);
    let photos = args.next().expect(usage);
    let sizes: serde_json::Value =
        serde_json::from_slice(&std::fs::read(Path::new(&photos).join("photos.json")).expect("photos.json")).unwrap();

    let fmt = time::macros::format_description!("[year]-[month]-[day]");
    let now = time::Date::parse(&today, &fmt).expect("today must be YYYY-MM-DD");
    let day = |n: i64| (now - time::Duration::days(n)).format(&fmt).unwrap();
    let years_ago = |n: i32| now.replace_year(now.year() - n).unwrap_or(now).format(&fmt).unwrap();

    let d = Demo { store: Store::open(path.clone()).expect("open"), photos, sizes };

    // Today: the entry the editor screenshot shows, a photo, two events and
    // the locked note.
    d.entry(
        &day(0),
        Some("07:40"),
        Some("Mist on the ridge"),
        "Up before the alarm, so I took the long way round the reservoir before work. The valley was **completely full of mist** and the ridge stood out above it like an island.
        Ten minutes without seeing anyone. I want to do this ==more often==, not just when I can't sleep.
        # Today
        [ ] Post the parcel back to Mum
        [ ] Ring the garage about the MOT
        [ ] Book club: finish the last three chapters
        [ ] Dentist at half two
        > *Small walks count.* Note to self from last winter, still true.",
        Some(4.0),
        &["Walk"],
        false,
    );
    d.photo(&day(0), "mist", "07:12", Some("The reservoir path"));
    d.event(&day(0), "Dentist", Some("14:30"), Some("15:00"), Some("High Street Dental"));
    d.event(&day(0), "Book club", Some("19:30"), Some("21:00"), Some("The Crown"));

    d.entry(&day(1), Some("14:00"), Some("Pottery, week four"),
        "Finally centred the clay without it wobbling off. The bowl is lopsided but it is *mine*. Ruth says next week we glaze.",
        Some(4.5), &["Class"], false);
    d.entry(&day(1), Some("17:20"), Some("Stubble fields"),
        "Walked home the long way, out past the farm to the long field. The light was low and gold by five and the whole valley smelt of woodsmoke.",
        Some(4.5), &["Walk"], false);
    d.photo(&day(1), "field", "17:05", None);
    d.entry(&day(1), Some("12:50"), None,
        "Lunch on the bench outside the library. Two starlings arguing over a crust for the whole half hour.",
        Some(3.0), &[], false);
    d.entry(&day(2), Some("18:30"), None,
        "Long day of spreadsheets. Came home, made a proper risotto and didn't look at a screen until nine. Better.",
        Some(3.0), &["Cooking"], false);
    // Written up a day late: no time, the moment had gone.
    d.entry(&day(3), None, Some("Monday, written up on Tuesday"),
        "Train delayed both ways, so I read most of Middlemarch on the platform. Dorothea is making every mistake I would have made.",
        Some(2.5), &["Reading"], false);
    d.entry(&day(5), Some("10:20"), Some("Last of the beans"),
        "Cleared the runner beans and dug over the bed. Three carrots the size of my thumb and one enormous squash nobody can explain. Saved seed from the best pods for next year.",
        Some(5.0), &["Allotment"], true);
    d.photo(&day(5), "hills", "11:05", Some("Across the valley from plot 14"));
    d.event(&day(5), "Allotment open day", Some("10:00"), Some("13:00"), Some("Hill Lane allotments"));
    d.entry(&day(5), Some("20:40"), Some("Dinner at Priya's"),
        "Dal, three kinds of pickle and far too much rice. We planned the walk for half term and argued happily about the route.",
        Some(4.5), &["Friends"], false);
    d.entry(&day(6), Some("19:05"), None,
        "End of a long week. Bath, tea, early night. Nothing else to report and that is fine.",
        Some(3.0), &[], false);
    d.entry(&day(8), None, None,
        "A quiet day I nearly forgot to write down. Fixed the bike's back brake with a video and a lot of patience.",
        Some(3.5), &[], false);

    // A three-day trip, written up as one entry.
    let trip = d.entry(&day(12), Some("18:00"), Some("Three days on the coast"),
        "Harbour at low tide on the first afternoon, boats leaning on the sand. Walked the cliff path to the next village and back on the second day. Fish and chips on the wall both nights.
        # Worth remembering
        - The café by the lifeboat station opens at eight
        - **Bring a second jumper.** The wind off the sea is no joke
        - Next time, the ferry across the estuary",
        Some(5.0), &["Travel", "Walk"], true);
    d.store.set_end_date(trip, Some(day(10))).unwrap();
    let harbour = d.photo(&day(12), "harbour", "15:30", Some("Low tide"));
    d.photo(&day(12), "coast", "18:40", Some("Evening from the cliff path"));
    d.event(&day(12), "Train to the coast", Some("09:12"), Some("11:40"), Some("Platform 4"));
    d.store.set_day_preview(None, day(12), PreviewChoice::Photo { id: harbour }).unwrap();

    d.entry(&day(13), Some("22:00"), Some("Rain"),
        "Rain all evening. Packed for the coast and found the good flask at the back of the cupboard.",
        Some(3.0), &[], false);
    d.entry(&day(15), Some("07:10"), None,
        "Ran the river loop, 5k in 29 minutes. Legs heavy for the first kilometre, then fine.",
        Some(4.0), &["Run"], false);
    d.entry(&day(16), Some("20:30"), Some("A secondhand Middlemarch"),
        "Found a 1965 Penguin edition in the charity shop for a pound, with someone's bus ticket from 1987 still inside as a bookmark.",
        Some(4.0), &["Reading"], false);
    d.entry(&day(18), Some("16:00"), Some("Choir"),
        "First rehearsal for the winter concert. The altos are outnumbered again, so I sang loud enough for three.",
        Some(4.0), &["Choir"], false);
    d.entry(&day(19), None, Some("Mum's birthday"),
        "Lunch at the garden centre café, by request. She opened the scarf, then my card, then told the waitress all about both.",
        Some(5.0), &["Family"], true);
    d.entry(&day(21), Some("19:45"), Some("October"),
        "Clocks go back soon. Making a list of things for the dark evenings: finish the jumper, learn three songs on the ukulele, write here every day.",
        Some(3.5), &[], false);
    d.entry(&day(23), Some("08:05"), None,
        "First morning cold enough to see my breath at the bus stop.",
        Some(3.0), &[], false);
    d.entry(&day(25), Some("11:00"), Some("Bread"),
        "Sourdough attempt number six. Better crumb, still too dense in the middle. Longer prove next time.",
        Some(3.5), &["Cooking"], false);

    // The same date in earlier years, for On This Day.
    d.entry(&years_ago(1), Some("17:30"), Some("First frost"),
        "First frost on the allotment. Brought the last tomatoes in to ripen on the windowsill.",
        Some(4.0), &["Allotment"], false);
    d.entry(&years_ago(3), Some("12:30"), None,
        "Picnic in the park with the whole family in October sunshine. Too much food, as usual.",
        Some(5.0), &["Family"], false);

    // Locked notes on, with one on today.
    d.store.set_lock_password(None, LOCK_PASSWORD.into()).unwrap();
    d.store
        .set_locked_note(None, day(0), LOCK_PASSWORD.into(),
            "Thinking about the job in Leeds. Pros: the team, the money, being nearer Priya. Cons: leaving the allotment, starting over. Decide by Friday.".into())
        .unwrap();

    drop(d);
    println!("seeded {path} (locked note password: {LOCK_PASSWORD})");
}
