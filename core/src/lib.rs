//! Dailylog's shared core: the local database, the journal model, search,
//! stats and sync. Every platform's UI calls into this through UniFFI; nothing
//! here knows about any UI toolkit. SPEC.md §3 is the design.

uniffi::setup_scaffolding!();

#[cfg(feature = "capi")]
pub mod capi;
mod changes;
mod crypto;
mod db;
pub mod doc;
mod error;
mod hlc;
mod model;
mod pairing;
mod search;
mod store;

pub use error::CoreError;
pub use model::*;
pub use pairing::*;
pub use store::Store;
