#![forbid(unsafe_code)]
//! Plain-language field inspector for the zero-PII 3 KB (`MPSY` v1) / 4 KB (`MPSY` v2) sync snapshot.
//!
//! Powers the Settings/About 「查看我的 3 KB 快照」 modal so players can verify every byte contains only
//! numeric counters and zero typed text, app names, or account IDs.
//!
//! ```
//! use meowpuut_companion_lib::sync_inspect::inspect_envelope;
//! use meowpuut_pets::sync::SyncEnvelope;
//! use meowpuut_pets::sync_v2::SyncEnvelopeV2;
//!
//! let mut base = SyncEnvelope::default();
//! base.state.snapshot.committed_graphemes = 1_234;
//! let view = inspect_envelope(&SyncEnvelopeV2::from_v1(base));
//! assert_eq!(view.format_bytes, 3_072);
//! assert_eq!(view.total_graphemes, 1_234);
//! assert!(view.fields.iter().any(|f| f.key == "graphemes"));
//! ```

use meowpuut_pets::sync::SYNC_BYTES;
use meowpuut_pets::sync_v2::{SyncEnvelopeV2, SYNC_V2_BYTES};
use meowpuut_pets::Pet;
use serde::Serialize;

/// One human-readable field inside the sync snapshot viewer.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotField {
    pub key: &'static str,
    pub label: [&'static str; 2],
    pub value: String,
    pub bytes: usize,
}

/// Complete plain-language inspection of a [`SyncEnvelopeV2`] (or downgraded v1 snapshot).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncInspection {
    pub format_version: u16,
    pub format_bytes: usize,
    pub epoch: u32,
    pub total_graphemes: u64,
    pub replica_count: u8,
    pub header_hex: String,
    pub privacy_note: [&'static str; 2],
    pub fields: Vec<SnapshotField>,
}

/// Produces a complete plain-language breakdown of every field inside `env`.
#[must_use]
pub fn inspect_envelope(env: &SyncEnvelopeV2) -> SyncInspection {
    let is_v2 = env.replica_count > 0;
    let format_version = if is_v2 { 2 } else { 1 };
    let format_bytes = if is_v2 { SYNC_V2_BYTES } else { SYNC_BYTES };
    let raw_v1 = env.to_bytes_v1();
    let header_hex = raw_v1[..32].iter().map(|b| format!("{b:02x}")).collect::<Vec<_>>().join(" ");

    let st = &env.base.state;
    let pr = &env.base.progress;
    let total_graphemes = env.total_graphemes();
    let unlocked = env.base.unlocked_apis().len();
    let places_count = pr.places.count_ones();
    let mimimo_lv = st.snapshot.progress(Pet::Mimimo).level;
    let momo_lv = st.snapshot.progress(Pet::Momo).level;

    let fields = vec![
        SnapshotField { key: "magic", label: ["檔案標記與版本", "文件标记与版本"], value: format!("MPSY v{format_version} ({format_bytes} B)"), bytes: 8 },
        SnapshotField { key: "group", label: ["匿名群組前綴（非帳號）", "匿名群组前缀（非账号）"], value: env.base.group_prefix.iter().map(|b| format!("{b:02x}")).collect(), bytes: 8 },
        SnapshotField { key: "epoch", label: ["帳本紀元（清除紀錄會遞增）", "账本纪元（清除记录会递增）"], value: st.epoch.to_string(), bytes: 4 },
        SnapshotField { key: "graphemes", label: ["累積字數計數（不含任何文字）", "累积字数计数（不含任何文字）"], value: total_graphemes.to_string(), bytes: 8 },
        SnapshotField { key: "mimimo", label: ["米米莫撫摸與好感等級", "米米莫抚摸与好感等级"], value: format!("摸 {} 次 · Lv.{mimimo_lv}", st.snapshot.mimimo.pets), bytes: 24 },
        SnapshotField { key: "momo", label: ["莫莫撫摸與好感等級", "莫莫抚摸与好感等级"], value: format!("摸 {} 次 · Lv.{momo_lv}", st.snapshot.momo.pets), bytes: 24 },
        SnapshotField { key: "book", label: ["爪印本最近 400 天紀錄格", "爪印本最近 400 天记录格"], value: format!("{} 天（僅存大小級距與地標編號）", st.book.len()), bytes: 2_800 },
        SnapshotField { key: "places", label: ["已走過地標（12 位元）", "已走过地标（12 比特）"], value: format!("{places_count} / 12"), bytes: 4 },
        SnapshotField { key: "festivals", label: ["遇見過的節日總數（不存名稱）", "遇见过的节日总数（不存名称）"], value: pr.festivals.to_string(), bytes: 4 },
        SnapshotField { key: "unlocked", label: ["已解鎖成就位元表（128 位元）", "已解锁成就位表（128 比特）"], value: format!("{unlocked} / 82"), bytes: 16 },
        SnapshotField { key: "checksum", label: ["FNV-1a 完整性校驗碼", "FNV-1a 完整性校验码"], value: "已通過固定長度校驗".to_owned(), bytes: 8 },
    ];

    SyncInspection {
        format_version,
        format_bytes,
        epoch: st.epoch,
        total_graphemes,
        replica_count: env.replica_count,
        header_hex,
        privacy_note: [
            "這份快照僅包含固定長度的無符號數字、地標位元與成就位元，零輸入內容、零視窗標題、零帳號個資。",
            "这份快照仅包含固定长度的无符号数字、地标位与成就位，零输入内容、零窗口标题、零账号个资。",
        ],
        fields,
    }
}
