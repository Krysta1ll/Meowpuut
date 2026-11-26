//! The two file habits every Meowpuut process shares: an exclusive lock on a side file, and replacing a file by
//! writing a temporary one next to it and renaming it over.
//!
//! The lock is the same one the input methods take: `flock` on macOS (`Sources/PetLedger.swift`,
//! `Sources/SettingsStore.swift`) and `LockFileEx` on Windows (`Windows/PetLedger.cpp`, which locks byte 0; std locks
//! the whole file, and the two ranges overlap, so they exclude each other). Like the text service, a busy lock is
//! retried for a short while instead of waiting forever.

use std::fs::{self, File, OpenOptions, TryLockError};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::Duration;

/// How long `FileLock::acquire` keeps trying: 100 tries 20 ms apart (the companion is not on anyone's key path, so it
/// can wait longer than the text service's 250 ms).
pub const LOCK_ATTEMPTS: u32 = 100;
const LOCK_PAUSE: Duration = Duration::from_millis(20);

/// Held for its lifetime; dropping it unlocks.
pub struct FileLock {
    file: File,
}

impl FileLock {
    pub fn acquire(path: &Path, attempts: u32) -> io::Result<Self> {
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir)?;
        }
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let file = options.open(path)?;
        for attempt in 0..attempts.max(1) {
            match file.try_lock() {
                Ok(()) => return Ok(Self { file }),
                Err(TryLockError::WouldBlock) => {
                    if attempt + 1 < attempts {
                        thread::sleep(LOCK_PAUSE);
                    }
                }
                Err(TryLockError::Error(error)) => return Err(error),
            }
        }
        Err(io::Error::new(io::ErrorKind::WouldBlock, "another Meowpuut process holds the lock"))
    }
}

impl Drop for FileLock {
    fn drop(&mut self) {
        let _ = self.file.unlock();
    }
}

/// The whole file, or empty when it does not exist. Anything longer than `limit` is not ours: an error.
pub fn read_limited(path: &Path, limit: u64) -> io::Result<Vec<u8>> {
    let file = match File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error),
    };
    let mut bytes = Vec::new();
    file.take(limit + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > limit {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "file is larger than expected"));
    }
    Ok(bytes)
}

/// Writes `bytes` to `<file>.tmp<pid>`, flushes it to disk and renames it over `file`.
pub fn replace(file: &Path, bytes: &[u8]) -> io::Result<()> {
    let temporary = temporary_path(file);
    let result = (|| {
        let mut out = File::create(&temporary)?;
        out.write_all(bytes)?;
        out.sync_all()?;
        drop(out);
        fs::rename(&temporary, file)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn temporary_path(file: &Path) -> PathBuf {
    let mut name = file.file_name().map(|n| n.to_os_string()).unwrap_or_default();
    name.push(format!(".tmp{}", std::process::id()));
    file.with_file_name(name)
}
