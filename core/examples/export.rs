//! Exports a journal database the way the apps do. Dev only; never shipped.
//!
//!   cargo run --example export -- <journal.sqlite> <out file> [json|markdown|html|text]

use librelog_core::{ExportFormat, Store};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let [_, db, out, rest @ ..] = args.as_slice() else {
        eprintln!("usage: export <journal.sqlite> <out file> [json|markdown|html|text]");
        std::process::exit(2);
    };
    let format = match rest.first().map(String::as_str).unwrap_or("json") {
        "json" => ExportFormat::Json,
        "markdown" => ExportFormat::Markdown,
        "html" => ExportFormat::Html,
        "text" => ExportFormat::Text,
        f => panic!("unknown format {f}"),
    };
    let store = Store::open(db.clone()).expect("open journal");
    let s = store.export(format, out.clone(), None).expect("export");
    println!("exported {} entries to {}", s.entries, s.path);
}
