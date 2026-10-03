/// Every error the core hands to the apps. Messages are for logs, not for
/// showing to users; the variant is what the UI branches on.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("database: {0}")]
    Database(String),
    #[error("not found: {0}")]
    NotFound(String),
    #[error("invalid input: {0}")]
    Invalid(String),
    #[error("sync: {0}")]
    Sync(String),
    #[error("crypto: {0}")]
    Crypto(String),
}

impl From<rusqlite::Error> for CoreError {
    fn from(e: rusqlite::Error) -> Self {
        match e {
            rusqlite::Error::QueryReturnedNoRows => CoreError::NotFound("row".into()),
            other => CoreError::Database(other.to_string()),
        }
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::Invalid(e.to_string())
    }
}

pub type Result<T> = std::result::Result<T, CoreError>;
