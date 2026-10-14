//! Imports a file into a journal database the way the apps do, printing the
//! preview first. Dev only; never shipped.
//!
//!   cargo run --example import -- <path to journal.sqlite> <file to import>

use librelog_core::Store;

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(db), Some(file)) = (args.next(), args.next()) else {
        eprintln!("usage: import <journal.sqlite> <file>");
        std::process::exit(2);
    };
    let store = Store::open(db).expect("open");
    let p = store.preview_import(file.clone()).expect("preview");
    println!("{:?}: {} entries ({} already here), {:?} – {:?}, {} tags", p.source, p.entries, p.already_imported, p.first_date, p.last_date, p.tags);
    for w in &p.warnings {
        println!("  ! {w}");
    }
    let r = store.import_file(file, None).expect("import");
    println!("imported {}, skipped {}", r.imported, r.skipped);
}
