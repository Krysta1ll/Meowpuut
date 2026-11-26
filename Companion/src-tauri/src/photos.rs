//! 生活照 (FINAL.md §2.5, 「照手機」): the public photos of the two cats, as the phones show them. The same read-only
//! Hong Kong Cloud Storage bucket, the same `photos.json` (version 1) and `photos/<file>.jpg`, the same daily order
//! ([`shuffle`] is the iOS `DailyPhotoOrder` and Android `DailyPhotoOrder` bit for bit; the golden vector of both is a test
//! here), and the same rules for what is accepted (a safe `.jpg` name, a JPEG, at most 10 MB).
//!
//! What it sends: plain GETs of the bucket's two kinds of URL, with a generic User-Agent, an `Accept` on the manifest and
//! nothing else: no cookie (the client has none), no identifier, no account, no referrer. Nothing about the user is in the
//! request or the response is stored beyond the files themselves.
//!
//! What it never does: block the window. The page asks for [`Photos::today`], which only reads the cache; the network is
//! touched by [`Photos::refresh`] (a thread of its own, once a window open, again after five minutes if it failed) and by
//! [`Photos::image`] for a photo that is not cached yet (an async command, one download per file at a time). With no
//! connection the cached manifest and the cached files are shown as they are; with neither, the list is empty and the page
//! says so.
//!
//! The cache is `Photos/` in the app's data folder (a trial folder keeps its own): `manifest.json` and the files, written to
//! a temp name and renamed. Files the manifest no longer lists are removed when a new manifest arrives.

use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub const BASE_URL: &str = "https://storage.googleapis.com/meowpuut-hk-photos-0285872710";
pub const MANIFEST_URL: &str = "https://storage.googleapis.com/meowpuut-hk-photos-0285872710/photos.json";
/// A photo larger than this is refused (the phones' limit).
pub const MAX_IMAGE_BYTES: u64 = 10 * 1024 * 1024;
const MAX_MANIFEST_BYTES: u64 = 1 << 20;
const RETRY_AFTER: Duration = Duration::from_secs(300);
const USER_AGENT: &str = "photo-client/1";

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Photo {
    pub file: String,
    pub width: u32,
    pub height: u32,
    #[serde(default)]
    pub cats: Vec<String>,
    #[serde(default)]
    pub month: Option<String>,
    #[serde(default)]
    pub caption: Option<String>,
}

/// A safe file name: ASCII letters, digits, `.`, `_`, `-`, no `..`, a `.jpg` or `.jpeg` (what the phones accept).
#[must_use]
pub fn is_safe_filename(name: &str) -> bool {
    let ok = !name.is_empty() && name.len() <= 128 && !name.contains("..") && name.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'));
    let lower = name.to_ascii_lowercase();
    ok && (lower.ends_with(".jpg") || lower.ends_with(".jpeg"))
}

/// The manifest as the phones read it: version 1, entries with a safe name and a size, the first of a name wins.
#[must_use]
pub fn parse_manifest(bytes: &[u8]) -> Option<Vec<Photo>> {
    #[derive(Deserialize)]
    struct Root {
        version: i64,
        photos: Vec<serde_json::Value>,
    }
    let root: Root = serde_json::from_slice(bytes).ok()?;
    if root.version != 1 {
        return None;
    }
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for value in root.photos {
        let Ok(photo) = serde_json::from_value::<Photo>(value) else { continue };
        if !is_safe_filename(&photo.file) || photo.width == 0 || photo.height == 0 || !seen.insert(photo.file.clone()) {
            continue;
        }
        let cats = photo.cats.into_iter().filter(|c| !c.is_empty()).collect();
        let nonempty = |s: Option<String>| s.filter(|s| !s.is_empty());
        out.push(Photo { cats, month: nonempty(photo.month), caption: nonempty(photo.caption), ..photo });
    }
    Some(out)
}

const SEED_MASK: u64 = 0x4D45_4F57_5055_5554;
const GAMMA: u64 = 0x9E37_79B9_7F4A_7C15;
const MIX1: u64 = 0xBF58_476D_1CE4_E5B9;
const MIX2: u64 = 0x94D0_49BB_1331_11EB;

fn next_mix(state: u64) -> (u64, u64) {
    let next = state.wrapping_add(GAMMA);
    let mut z = next;
    z = (z ^ (z >> 30)).wrapping_mul(MIX1);
    z = (z ^ (z >> 27)).wrapping_mul(MIX2);
    (next, z ^ (z >> 31))
}

fn shuffled_by_day(sorted: &[Photo], day_num: i64) -> Vec<Photo> {
    let mut v = sorted.to_vec();
    let mut state = (day_num as u64) ^ SEED_MASK;
    for i in (1..v.len()).rev() {
        let (next, rnd) = next_mix(state);
        state = next;
        let j = (rnd % (i as u64 + 1)) as usize;
        v.swap(i, j);
    }
    v
}

/// Today's order: the photos sorted by file name, shuffled by the day (a Fisher-Yates seeded by the day number), with the
/// phones' guard that the lead photo is never the same two days in a row. `day_num` is the local day index (days since
/// 1970-01-01), which is what the phones' civil-day arithmetic gives for the date.
#[must_use]
pub fn shuffle(photos: &[Photo], day_num: i64) -> Vec<Photo> {
    let mut sorted = photos.to_vec();
    sorted.sort_by(|a, b| a.file.cmp(&b.file));
    let n = sorted.len();
    if n <= 1 {
        return sorted;
    }
    let mut result = shuffled_by_day(&sorted, day_num);
    let odd = day_num & 1 != 0;
    if n == 2 {
        if odd {
            result.swap(0, 1);
        }
        return result;
    }
    if odd {
        let even_lead = |day: i64| shuffled_by_day(&sorted, day)[0].file.clone();
        let (prev, next) = (even_lead(day_num - 1), even_lead(day_num + 1));
        if result[0].file == prev || result[0].file == next {
            if let Some(rep) = (1..n).find(|&k| result[k].file != prev && result[k].file != next) {
                result.swap(0, rep);
            }
        }
    }
    result
}

/// One photo as the page is told of it.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotoView {
    #[serde(flatten)]
    pub photo: Photo,
    /// The file is in the cache already (the page can show it without a wait).
    pub cached: bool,
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhotosView {
    /// Today's order. Empty: no manifest cached yet.
    pub photos: Vec<PhotoView>,
    /// A manifest is cached (so an empty list means there are no photos, not that none came yet).
    pub have_manifest: bool,
}

#[derive(Default)]
struct Net {
    /// When the last manifest fetch began, and whether it worked.
    last: Option<(Instant, bool)>,
    running: bool,
}

/// The photo cache and its two ways to the network.
pub struct Photos {
    dir: PathBuf,
    net: Mutex<Net>,
    /// Files being downloaded now: one download per file at a time.
    busy: Mutex<HashSet<String>>,
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .user_agent(USER_AGENT)
        .timeout_connect(Duration::from_secs(8))
        .timeout_read(Duration::from_secs(15))
        .timeout(Duration::from_secs(40))
        .redirects(0)
        .build()
}

fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension(format!("tmp-{}", std::process::id()));
    std::fs::write(&tmp, bytes)?;
    std::fs::rename(&tmp, path).inspect_err(|_| {
        let _ = std::fs::remove_file(&tmp);
    })
}

/// A GET of at most `cap` bytes; `None` for anything but a plain 200.
fn get(url: &str, accept: Option<&str>, cap: u64) -> Option<Vec<u8>> {
    let mut request = agent().get(url);
    if let Some(accept) = accept {
        request = request.set("Accept", accept);
    }
    let response = request.call().ok()?;
    if response.status() != 200 {
        return None;
    }
    let mut bytes = Vec::new();
    response.into_reader().take(cap + 1).read_to_end(&mut bytes).ok()?;
    (bytes.len() as u64 <= cap).then_some(bytes)
}

impl Photos {
    pub fn new(data_dir: &Path) -> Self {
        Self { dir: data_dir.join("Photos"), net: Mutex::new(Net::default()), busy: Mutex::new(HashSet::new()) }
    }

    fn manifest_file(&self) -> PathBuf {
        self.dir.join("manifest.json")
    }

    fn cached_manifest(&self) -> Option<Vec<Photo>> {
        parse_manifest(&std::fs::read(self.manifest_file()).ok()?)
    }

    /// Today's photos from the cache only. Never touches the network.
    #[must_use]
    pub fn today(&self, day: u32) -> PhotosView {
        let Some(photos) = self.cached_manifest() else { return PhotosView::default() };
        let photos = shuffle(&photos, i64::from(day)).into_iter().map(|photo| PhotoView { cached: self.dir.join(&photo.file).is_file(), photo }).collect();
        PhotosView { photos, have_manifest: true }
    }

    /// Fetches the manifest on a thread of its own (once per window open, again five minutes after a failure) and
    /// calls `done(changed)` when it is over. Returns false when nothing was started.
    pub fn refresh(&'static self, done: impl FnOnce(bool) + Send + 'static) -> bool {
        {
            let mut net = self.net.lock().unwrap_or_else(|e| e.into_inner());
            let recent = net.last.is_some_and(|(at, ok)| ok || at.elapsed() < RETRY_AFTER);
            if net.running || recent {
                return false;
            }
            net.running = true;
            net.last = Some((Instant::now(), false));
        }
        std::thread::spawn(move || {
            let changed = self.fetch_manifest();
            let mut net = self.net.lock().unwrap_or_else(|e| e.into_inner());
            net.running = false;
            net.last = Some((Instant::now(), changed.is_some()));
            drop(net);
            done(changed == Some(true));
        });
        true
    }

    /// `Some(changed)` when a manifest came and is cached, `None` when it did not.
    fn fetch_manifest(&self) -> Option<bool> {
        let bytes = get(MANIFEST_URL, Some("application/json"), MAX_MANIFEST_BYTES)?;
        let photos = parse_manifest(&bytes).filter(|p| !p.is_empty())?;
        std::fs::create_dir_all(&self.dir).ok()?;
        let changed = std::fs::read(self.manifest_file()).ok().as_deref() != Some(bytes.as_slice());
        write_atomic(&self.manifest_file(), &bytes).ok()?;
        // Files the manifest no longer lists go.
        let keep: HashSet<&str> = photos.iter().map(|p| p.file.as_str()).chain(["manifest.json"]).collect();
        if let Ok(entries) = std::fs::read_dir(&self.dir) {
            for entry in entries.flatten() {
                if !keep.contains(entry.file_name().to_string_lossy().as_ref()) {
                    let _ = std::fs::remove_file(entry.path());
                }
            }
        }
        Some(changed)
    }

    /// The photo's bytes: from the cache, else downloaded (and cached). The file must be one the cached manifest lists.
    pub fn image(&self, file: &str) -> Result<Vec<u8>, String> {
        if !is_safe_filename(file) || !self.cached_manifest().is_some_and(|m| m.iter().any(|p| p.file == file)) {
            return Err("not a listed photo".to_owned());
        }
        let path = self.dir.join(file);
        if let Ok(bytes) = std::fs::read(&path) {
            return Ok(bytes);
        }
        // One download of a file at a time; a second caller waits for the first and then reads the cache.
        loop {
            if self.busy.lock().unwrap_or_else(|e| e.into_inner()).insert(file.to_owned()) {
                break;
            }
            std::thread::sleep(Duration::from_millis(50));
            if let Ok(bytes) = std::fs::read(&path) {
                return Ok(bytes);
            }
        }
        let result = (|| {
            let bytes = get(&format!("{BASE_URL}/photos/{file}"), None, MAX_IMAGE_BYTES).ok_or("no connection")?;
            if bytes.len() < 4 || bytes[0] != 0xFF || bytes[1] != 0xD8 {
                return Err("not a JPEG");
            }
            std::fs::create_dir_all(&self.dir).map_err(|_| "no cache folder")?;
            write_atomic(&path, &bytes).map_err(|_| "cache write failed")?;
            Ok(bytes)
        })();
        self.busy.lock().unwrap_or_else(|e| e.into_inner()).remove(file);
        result.map_err(str::to_owned)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn photo(file: &str) -> Photo {
        Photo { file: file.to_owned(), width: 1000, height: 1500, cats: vec!["momo".to_owned()], month: None, caption: None }
    }

    /// 2026-11-19 as a day index (the phones' `civilDay`).
    const NOV_19_2026: i64 = 20_776;

    #[test]
    fn the_daily_order_is_the_phones_golden_vector() {
        let input: Vec<Photo> = ["a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg"].iter().map(|f| photo(f)).collect();
        let order: Vec<String> = shuffle(&input, NOV_19_2026).into_iter().map(|p| p.file).collect();
        assert_eq!(order, ["c.jpg", "d.jpg", "a.jpg", "e.jpg", "b.jpg"], "iOS and Android agree on this order");
    }

    #[test]
    fn the_order_is_canonical_a_bijection_and_the_lead_changes_every_day() {
        let sample: Vec<Photo> = (1..=24).map(|i| photo(&format!("photo-{:04x}.jpg", i * 7919))).collect();
        let mut reversed = sample.clone();
        reversed.reverse();
        assert_eq!(shuffle(&sample, NOV_19_2026), shuffle(&reversed, NOV_19_2026), "independent of the manifest's order");
        let mut leads = HashSet::new();
        let mut previous = String::new();
        for day in 20_454..20_454 + 365 {
            let order = shuffle(&sample, day);
            assert_eq!(order.iter().map(|p| &p.file).collect::<HashSet<_>>().len(), 24);
            assert_ne!(order[0].file, previous, "the lead rotates off yesterday's on day {day}");
            previous = order[0].file.clone();
            leads.insert(previous.clone());
        }
        assert!(leads.len() >= 20, "every photo gets its turn ({})", leads.len());
        assert_eq!(shuffle(&[], 5), vec![]);
        assert_eq!(shuffle(&[photo("a.jpg")], 5).len(), 1);
        let two = [photo("a.jpg"), photo("b.jpg")];
        assert_ne!(shuffle(&two, 20_775)[0].file, shuffle(&two, 20_776)[0].file);
    }

    #[test]
    fn file_names_are_safe_or_refused() {
        for ok in ["photo-07382c07effd.jpg", "CAT_1.JPEG"] {
            assert!(is_safe_filename(ok), "{ok}");
        }
        for bad in ["../secret.jpg", "dir/photo.jpg", "photo.png", "", "a..b.jpg", "a b.jpg", "x.jpg\0"] {
            assert!(!is_safe_filename(bad), "{bad:?}");
        }
    }

    #[test]
    fn the_manifest_keeps_only_what_the_phones_keep() {
        let json = br#"{"version":1,"photos":[
            {"file":"valid-1.jpg","width":1000,"height":1500,"cats":["momo"],"month":"2026-01","caption":""},
            {"file":"../evil.jpg","width":1000,"height":1500,"cats":["mimimo"]},
            {"file":"valid-1.jpg","width":1000,"height":1500,"cats":["momo"]},
            {"file":"zero.jpg","width":0,"height":100,"cats":["momo"]},
            {"file":"odd.jpg","width":"x","height":100},
            {"file":"two.jpg","width":10,"height":10,"cats":["momo","","mimimo"]}]}"#;
        let parsed = parse_manifest(json).unwrap();
        assert_eq!(parsed.iter().map(|p| p.file.as_str()).collect::<Vec<_>>(), ["valid-1.jpg", "two.jpg"]);
        assert_eq!(parsed[0].month.as_deref(), Some("2026-01"));
        assert_eq!(parsed[0].caption, None, "an empty caption is none");
        assert_eq!(parsed[1].cats, ["momo", "mimimo"]);
        assert!(parse_manifest(br#"{"version":2,"photos":[]}"#).is_none());
        assert!(parse_manifest(b"not json").is_none());
    }

    #[test]
    fn today_reads_the_cache_only_and_offline_is_an_empty_list_or_the_cached_one() {
        let dir = tempfile::tempdir().unwrap();
        let photos = Box::leak(Box::new(Photos::new(dir.path())));
        let none = photos.today(20_776);
        assert!(!none.have_manifest && none.photos.is_empty());
        std::fs::create_dir_all(dir.path().join("Photos")).unwrap();
        std::fs::write(dir.path().join("Photos/manifest.json"), br#"{"version":1,"photos":[{"file":"a.jpg","width":1,"height":1},{"file":"b.jpg","width":1,"height":1}]}"#).unwrap();
        std::fs::write(dir.path().join("Photos/a.jpg"), [0xFF, 0xD8, 0, 0]).unwrap();
        let view = photos.today(20_776);
        assert!(view.have_manifest);
        assert_eq!(view.photos.len(), 2);
        assert_eq!(view.photos.iter().filter(|p| p.cached).map(|p| p.photo.file.as_str()).collect::<Vec<_>>(), ["a.jpg"]);
        // A cached file is served without any network; a name that is not listed is refused outright.
        assert_eq!(photos.image("a.jpg").unwrap(), [0xFF, 0xD8, 0, 0]);
        assert!(photos.image("../manifest.json").is_err());
        assert!(photos.image("c.jpg").is_err(), "not in the manifest");
    }
}
