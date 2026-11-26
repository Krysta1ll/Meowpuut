//! Asking 喵撲貓窩 "what am I doing now" ([`crate::share::DeskNow`]). The desk is the only process that sees the
//! foreground app; it answers with an `MPActivityCurrent` (272 bytes: state, category, full screen, and the app's
//! display name only when asked for and allowed). Nothing else can be asked: no history, no minutes, no bundle id.
//!
//! - macOS ([`mac::Xpc`]): the XPC Mach service `app.meowpuut.desk.activity` of the desk's LaunchAgent
//!   (`Desk/Sources/ActivityService.swift`, `docs/SETTINGS.md`). Request `{"v": 1, "app": bool}`, reply
//!   `{"v": 1, "current": data[272]}`. The desk accepts only a peer signed as `app.meowpuut.companion` (and, with a
//!   Developer ID, its own Team ID); this side requires `app.meowpuut.desk` the same way. An unsigned development
//!   build is therefore refused by the desk and shares nothing (see README「Steam」).
//! - Windows ([`win::Section`]): the desk's private-namespace shared section, layout 2 (`Windows/Desk/PulseChannel.h`):
//!   the `MPActivityCurrent` at offset 136 under its own seqlock word at 128. Only this user's processes at Medium
//!   integrity or higher can open it. The desk fills the name only while the registry value `ActivityShareAppName` is 1
//!   (which the companion sets exactly while sharing and 「顯示 App 名稱」 are on); this side returns it only when asked.
//! - [`Stub`]: `MEOWPUUT_DESK_NOW=code:VS Code` (development): a fixed answer, for trying the sharing mode without a
//!   signed desk. `fullscreen`, `idle`, `paused`, `hidden`, `off` work too.

use crate::share::DeskNow;
use meowpuut_activity::tracker::{Activity, Current};
use meowpuut_activity::Category;

pub const STUB_ENV: &str = "MEOWPUUT_DESK_NOW";

/// The platform's reader (or the development stub).
pub fn reader() -> Box<dyn DeskNow> {
    if let Some(stub) = std::env::var(STUB_ENV).ok().and_then(|v| Stub::parse(&v)) {
        return Box::new(stub);
    }
    #[cfg(target_os = "macos")]
    return Box::new(mac::Xpc::default());
    #[cfg(windows)]
    return Box::new(win::Section::default());
    #[cfg(not(any(target_os = "macos", windows)))]
    Box::new(Nobody)
}

/// Whether this platform's desk can give app names at all (FINAL.md §11.1 「顯示 App 名稱」): macOS answers the
/// XPC request's `app` flag, Windows reads the name from the section while `ActivityShareAppName` is 1.
pub const fn app_names_available(platform: crate::paths::Platform) -> bool {
    matches!(platform, crate::paths::Platform::Mac | crate::paths::Platform::Windows)
}

/// What a reader hands on: the name only when the caller asked for it (and the desk had one to give).
#[cfg(any(windows, test))]
fn with_app_only_if_wanted(mut current: Current, want_app: bool) -> Current {
    if !want_app {
        current.app = None;
    }
    current
}

impl DeskNow for Box<dyn DeskNow> {
    fn current(&mut self, want_app: bool) -> Option<Current> {
        (**self).current(want_app)
    }
}

/// No desk on this platform.
pub struct Nobody;

impl DeskNow for Nobody {
    fn current(&mut self, _want_app: bool) -> Option<Current> {
        None
    }
}

/// A fixed answer (development).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Stub(pub Current);

impl Stub {
    /// `category[:app name]`, `fullscreen[:category]`, `idle`, `paused`, `hidden`, `off`.
    pub fn parse(spec: &str) -> Option<Self> {
        let (head, rest) = spec.split_once(':').unwrap_or((spec, ""));
        let current = match head.trim() {
            "idle" => Current { activity: Activity::Idle, app: None, fullscreen: false },
            "paused" => Current { activity: Activity::Paused, app: None, fullscreen: false },
            "hidden" => Current { activity: Activity::Hidden, app: None, fullscreen: false },
            "off" => Current { activity: Activity::Off, app: None, fullscreen: false },
            "fullscreen" => Current {
                activity: Activity::Active(Category::from_code(rest.trim()).unwrap_or(Category::Media)),
                app: None,
                fullscreen: true,
            },
            code => Current {
                activity: Activity::Active(Category::from_code(code)?),
                app: Some(rest.trim().to_owned()).filter(|a| !a.is_empty()),
                fullscreen: false,
            },
        };
        Some(Self(current))
    }
}

impl DeskNow for Stub {
    fn current(&mut self, want_app: bool) -> Option<Current> {
        let mut c = self.0.clone();
        if !want_app {
            c.app = None;
        }
        Some(c)
    }
}

#[cfg(target_os = "macos")]
pub mod mac {
    //! libxpc, called directly (it is part of libSystem).

    use crate::share::{parse_current, DeskNow, CURRENT_BYTES};
    use block2::RcBlock;
    use meowpuut_activity::tracker::Current;
    use std::ffi::{c_char, c_int, c_void, CStr, CString};

    type XpcObject = *mut c_void;

    pub const SERVICE: &CStr = c"app.meowpuut.desk.activity";
    pub const DESK_IDENTIFIER: &str = "app.meowpuut.desk";
    const VERSION: u64 = 1;

    extern "C" {
        static _xpc_type_dictionary: u8;
        fn xpc_connection_create_mach_service(name: *const c_char, targetq: *mut c_void, flags: u64) -> XpcObject;
        fn xpc_connection_set_event_handler(connection: XpcObject, handler: &block2::Block<dyn Fn(XpcObject)>);
        fn xpc_connection_set_peer_code_signing_requirement(connection: XpcObject, requirement: *const c_char) -> c_int;
        fn xpc_connection_resume(connection: XpcObject);
        fn xpc_connection_cancel(connection: XpcObject);
        fn xpc_connection_send_message_with_reply_sync(connection: XpcObject, message: XpcObject) -> XpcObject;
        fn xpc_dictionary_create(keys: *const *const c_char, values: *const XpcObject, count: usize) -> XpcObject;
        fn xpc_dictionary_set_uint64(dictionary: XpcObject, key: *const c_char, value: u64);
        fn xpc_dictionary_set_bool(dictionary: XpcObject, key: *const c_char, value: bool);
        fn xpc_dictionary_get_uint64(dictionary: XpcObject, key: *const c_char) -> u64;
        fn xpc_dictionary_get_data(dictionary: XpcObject, key: *const c_char, length: *mut usize) -> *const c_void;
        fn xpc_get_type(object: XpcObject) -> *const c_void;
        fn xpc_release(object: XpcObject);
    }

    /// The code requirement for the desk: its signing identifier, plus this process's Team ID when it has one
    /// (`requirement(identifier:team:)` in `Sources/DeskPulse.swift`).
    pub fn requirement(team: Option<&str>) -> String {
        let base = format!("identifier \"{DESK_IDENTIFIER}\"");
        match team.filter(|t| !t.is_empty() && t.chars().all(|c| c.is_ascii_alphanumeric())) {
            Some(team) => format!("{base} and anchor apple generic and certificate leaf[subject.OU] = \"{team}\""),
            None => base,
        }
    }

    /// One connection, made on first use and again after an error.
    #[derive(Default)]
    pub struct Xpc {
        connection: Option<Connection>,
    }

    struct Connection {
        raw: XpcObject,
        _handler: RcBlock<dyn Fn(XpcObject)>,
    }

    // SAFETY: XPC connections are thread-safe objects; this one is used from one thread at a time (&mut self).
    unsafe impl Send for Connection {}

    impl Drop for Connection {
        fn drop(&mut self) {
            // SAFETY: `raw` is a connection this struct created and still owns.
            unsafe {
                xpc_connection_cancel(self.raw);
                xpc_release(self.raw);
            }
        }
    }

    impl Connection {
        fn open() -> Option<Self> {
            let requirement = CString::new(requirement(own_team().as_deref())).ok()?;
            // SAFETY: valid C strings; a NULL queue is the default target queue; flags 0 (a client).
            let raw = unsafe { xpc_connection_create_mach_service(SERVICE.as_ptr(), std::ptr::null_mut(), 0) };
            if raw.is_null() {
                return None;
            }
            // Replies come back through send_message_with_reply_sync; the handler only has to exist (libxpc requires
            // one before resume) and sees errors, which the next request notices anyway.
            let handler = RcBlock::new(|_event: XpcObject| {});
            let connection = Self { raw, _handler: handler };
            // SAFETY: `raw` is a fresh, suspended connection; the block is copied by libxpc.
            unsafe {
                xpc_connection_set_event_handler(raw, &connection._handler);
                let guarded = xpc_connection_set_peer_code_signing_requirement(raw, requirement.as_ptr()) == 0;
                // A suspended connection must not be released: resume it either way; without the check it is
                // cancelled (dropped) before anything is sent.
                xpc_connection_resume(raw);
                if !guarded {
                    return None;
                }
            }
            Some(connection)
        }

        fn ask(&self, want_app: bool) -> Result<Option<Current>, ()> {
            // SAFETY: every object created here is released here; keys are NUL-terminated literals.
            unsafe {
                let request = xpc_dictionary_create(std::ptr::null(), std::ptr::null(), 0);
                if request.is_null() {
                    return Err(());
                }
                xpc_dictionary_set_uint64(request, c"v".as_ptr(), VERSION);
                xpc_dictionary_set_bool(request, c"app".as_ptr(), want_app);
                let reply = xpc_connection_send_message_with_reply_sync(self.raw, request);
                xpc_release(request);
                if reply.is_null() {
                    return Err(());
                }
                let dictionary = std::ptr::addr_of!(_xpc_type_dictionary).cast::<c_void>();
                let out = if xpc_get_type(reply) != dictionary {
                    Err(()) // an error object: the desk is not running, or it refused this process's signature
                } else if xpc_dictionary_get_uint64(reply, c"v".as_ptr()) != VERSION {
                    Ok(None)
                } else {
                    let mut length = 0usize;
                    let data = xpc_dictionary_get_data(reply, c"current".as_ptr(), &mut length);
                    if data.is_null() || length != CURRENT_BYTES {
                        Ok(None)
                    } else {
                        Ok(parse_current(std::slice::from_raw_parts(data.cast::<u8>(), length)))
                    }
                };
                xpc_release(reply);
                out
            }
        }
    }

    impl DeskNow for Xpc {
        fn current(&mut self, want_app: bool) -> Option<Current> {
            if self.connection.is_none() {
                self.connection = Connection::open();
            }
            match self.connection.as_ref()?.ask(want_app) {
                Ok(current) => current,
                Err(()) => {
                    self.connection = None; // make a new one next time
                    None
                }
            }
        }
    }

    /// This process's Team ID; `None` for an ad-hoc or unsigned (development) build.
    pub fn own_team() -> Option<String> {
        use core_foundation::base::TCFType;
        use core_foundation::string::CFString;
        use core_foundation_sys::base::{CFRelease, CFTypeRef};
        use core_foundation_sys::dictionary::{CFDictionaryGetValue, CFDictionaryRef};
        use core_foundation_sys::string::CFStringRef;

        #[link(name = "Security", kind = "framework")]
        extern "C" {
            static kSecCodeInfoTeamIdentifier: CFStringRef;
            fn SecCodeCopySelf(flags: u32, code: *mut CFTypeRef) -> i32;
            fn SecCodeCopyStaticCode(code: CFTypeRef, flags: u32, static_code: *mut CFTypeRef) -> i32;
            fn SecCodeCopySigningInformation(code: CFTypeRef, flags: u32, information: *mut CFDictionaryRef) -> i32;
        }
        const SIGNING_INFORMATION: u32 = 1 << 1; // kSecCSSigningInformation

        // SAFETY: Security framework calls with out-pointers; each object obtained is released once.
        unsafe {
            let mut code: CFTypeRef = std::ptr::null();
            if SecCodeCopySelf(0, &mut code) != 0 || code.is_null() {
                return None;
            }
            let mut static_code: CFTypeRef = std::ptr::null();
            let ok = SecCodeCopyStaticCode(code, 0, &mut static_code) == 0 && !static_code.is_null();
            CFRelease(code);
            if !ok {
                return None;
            }
            let mut info: CFDictionaryRef = std::ptr::null();
            let ok = SecCodeCopySigningInformation(static_code, SIGNING_INFORMATION, &mut info) == 0 && !info.is_null();
            CFRelease(static_code);
            if !ok {
                return None;
            }
            let value = CFDictionaryGetValue(info, kSecCodeInfoTeamIdentifier.cast());
            let team = (!value.is_null()).then(|| CFString::wrap_under_get_rule(value.cast()).to_string());
            CFRelease(info.cast());
            team
        }
    }
}

#[cfg(windows)]
pub mod win {
    //! The desk's shared section, read-only (`Windows/Desk/PulseChannel.h`, layout 2).

    use crate::share::{parse_current, DeskNow, CURRENT_BYTES};
    use meowpuut_activity::tracker::Current;
    use std::sync::atomic::{fence, AtomicU32, Ordering};
    use std::time::{Duration, Instant};
    use windows::core::{w, PCWSTR};
    use windows::Win32::Foundation::{CloseHandle, HANDLE};
    use windows::Win32::Security::{CreateWellKnownSid, GetTokenInformation, TokenUser, WinMediumLabelSid, PSID, TOKEN_QUERY, TOKEN_USER};
    use windows::Win32::System::Memory::{MapViewOfFile, OpenFileMappingW, UnmapViewOfFile, FILE_MAP_READ, MEMORY_MAPPED_VIEW_ADDRESS};
    use windows::Win32::System::Threading::{
        AddIntegrityLabelToBoundaryDescriptor, AddSIDToBoundaryDescriptor, ClosePrivateNamespace, CreateBoundaryDescriptorW,
        DeleteBoundaryDescriptor, GetCurrentProcess, OpenPrivateNamespaceW, OpenProcessToken,
    };

    const BOUNDARY: PCWSTR = w!("Meowpuut.Desk");
    const ALIAS: PCWSTR = w!("Meowpuut");
    const SLOT: PCWSTR = w!("Meowpuut\\PulseSlot.v2");
    const SECTION_BYTES: usize = 512;
    const MAGIC: u32 = 0x5350_504D; // "MPPS"
    const VERSION: u16 = 2;
    const OFF_NOW_SEQ: usize = 128;
    const OFF_NOW: usize = 136;
    /// Like the input method: while the desk is not running, try to open the section at most this often.
    const RETRY: Duration = Duration::from_secs(3);

    #[derive(Default)]
    pub struct Section {
        open: Option<Open>,
        tried: Option<Instant>,
    }

    struct Open {
        boundary: HANDLE,
        namespace: HANDLE,
        mapping: HANDLE,
        view: MEMORY_MAPPED_VIEW_ADDRESS,
    }

    // SAFETY: plain kernel handles and a read-only view, used from one thread at a time (&mut self).
    unsafe impl Send for Open {}

    impl Drop for Open {
        fn drop(&mut self) {
            // SAFETY: each handle and the view were obtained by `Open::new` and are released once.
            unsafe {
                if !self.view.Value.is_null() {
                    let _ = UnmapViewOfFile(self.view);
                }
                if !self.mapping.is_invalid() {
                    let _ = CloseHandle(self.mapping);
                }
                if !self.namespace.is_invalid() {
                    let _ = ClosePrivateNamespace(self.namespace, 0);
                }
                if !self.boundary.is_invalid() {
                    DeleteBoundaryDescriptor(self.boundary);
                }
            }
        }
    }

    impl Open {
        fn new() -> Option<Self> {
            // SAFETY: Win32 calls with buffers sized as the API asks; every handle lands in `Open` and is released by
            // its Drop, also on the early returns.
            unsafe {
                let mut open = Self { boundary: HANDLE::default(), namespace: HANDLE::default(), mapping: HANDLE::default(), view: MEMORY_MAPPED_VIEW_ADDRESS::default() };
                // The boundary: this user's SID and the Medium integrity label (makeBoundary in PulseChannel.cpp).
                let mut token = HANDLE::default();
                OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token).ok()?;
                let mut user = [0u64; 64]; // TOKEN_USER + SID, 8-byte aligned
                let mut length = 0u32;
                let got = GetTokenInformation(token, TokenUser, Some(user.as_mut_ptr().cast()), std::mem::size_of_val(&user) as u32, &mut length);
                let _ = CloseHandle(token);
                got.ok()?;
                let sid = (*user.as_ptr().cast::<TOKEN_USER>()).User.Sid;
                let mut medium = [0u64; 9]; // SECURITY_MAX_SID_SIZE = 68
                let mut size = std::mem::size_of_val(&medium) as u32;
                CreateWellKnownSid(WinMediumLabelSid, None, Some(PSID(medium.as_mut_ptr().cast())), &mut size).ok()?;
                open.boundary = CreateBoundaryDescriptorW(BOUNDARY, 0);
                if open.boundary.is_invalid() {
                    return None;
                }
                AddSIDToBoundaryDescriptor(&mut open.boundary, sid).ok()?;
                AddIntegrityLabelToBoundaryDescriptor(&mut open.boundary, PSID(medium.as_mut_ptr().cast())).ok()?;
                open.namespace = OpenPrivateNamespaceW(open.boundary.0, ALIAS);
                if open.namespace.is_invalid() {
                    return None;
                }
                open.mapping = OpenFileMappingW(FILE_MAP_READ.0, false, SLOT).ok()?;
                open.view = MapViewOfFile(open.mapping, FILE_MAP_READ, 0, 0, SECTION_BYTES);
                if open.view.Value.is_null() {
                    return None;
                }
                Some(open)
            }
        }

        fn word(&self, at: usize) -> &AtomicU32 {
            // SAFETY: the view is SECTION_BYTES long and page-aligned; `at` is a multiple of 4 inside it; the desk
            // writes these words atomically (std::atomic<uint32_t> in PulseChannel.cpp).
            unsafe { &*self.view.Value.cast::<u8>().add(at).cast::<AtomicU32>() }
        }

        fn ready(&self) -> bool {
            let head = self.word(4).load(Ordering::Acquire);
            self.word(0).load(Ordering::Acquire) == MAGIC && (head & 0xFFFF) as u16 == VERSION && (head >> 16) as usize == SECTION_BYTES
        }

        /// `pulse::now`: the seqlock read. `None` while the desk is writing (try again) or not ready.
        fn now(&self) -> Option<[u8; CURRENT_BYTES]> {
            if !self.ready() {
                return None;
            }
            let seq = self.word(OFF_NOW_SEQ);
            let before = seq.load(Ordering::Acquire);
            if before & 1 != 0 {
                return None;
            }
            let mut bytes = [0u8; CURRENT_BYTES];
            for i in (0..CURRENT_BYTES).step_by(4) {
                bytes[i..i + 4].copy_from_slice(&self.word(OFF_NOW + i).load(Ordering::Relaxed).to_le_bytes());
            }
            fence(Ordering::Acquire);
            (seq.load(Ordering::Relaxed) == before).then_some(bytes)
        }
    }

    impl DeskNow for Section {
        /// The name comes back only when `want_app` (the desk also leaves it "" unless the gate is on).
        fn current(&mut self, want_app: bool) -> Option<Current> {
            if self.open.is_none() {
                if self.tried.is_some_and(|t| t.elapsed() < RETRY) {
                    return None;
                }
                self.tried = Some(Instant::now());
                self.open = Open::new();
            }
            let open = self.open.as_ref()?;
            for _ in 0..8 {
                if let Some(bytes) = open.now() {
                    return Some(super::with_app_only_if_wanted(parse_current(&bytes)?, want_app));
                }
                std::thread::yield_now();
            }
            if !open.ready() {
                self.open = None; // the desk quit and a new one may come
            }
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_stub_answers_like_the_desk() {
        let mut s = Stub::parse("code:VS Code").unwrap();
        assert_eq!(s.current(true).unwrap().app.as_deref(), Some("VS Code"));
        assert_eq!(s.current(false).unwrap().app, None, "no name unless asked");
        let f = Stub::parse("fullscreen:web").unwrap().0;
        assert_eq!((f.activity, f.fullscreen), (Activity::Active(Category::Web), true));
        assert_eq!(Stub::parse("idle").unwrap().0.activity, Activity::Idle);
        assert_eq!(Stub::parse("banana"), None);
    }

    #[test]
    fn the_windows_section_gives_the_name_only_when_asked() {
        let mut bytes = [0u8; crate::share::CURRENT_BYTES];
        bytes[0] = 4; // ACTIVE
        bytes[4] = Category::Code.index() as u8;
        bytes[16..22].copy_from_slice(b"VSCode");
        let parsed = crate::share::parse_current(&bytes).unwrap();
        assert_eq!(with_app_only_if_wanted(parsed.clone(), true).app.as_deref(), Some("VSCode"));
        assert_eq!(with_app_only_if_wanted(parsed, false).app, None);
        // An empty name field stays none; so does one in a state that never shows it.
        bytes[16] = 0;
        assert_eq!(with_app_only_if_wanted(crate::share::parse_current(&bytes).unwrap(), true).app, None);
        assert!(app_names_available(crate::paths::Platform::Windows) && app_names_available(crate::paths::Platform::Mac));
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn the_desks_requirement_matches_the_input_methods() {
        assert_eq!(mac::requirement(None), "identifier \"app.meowpuut.desk\"");
        assert_eq!(
            mac::requirement(Some("ABCDE12345")),
            "identifier \"app.meowpuut.desk\" and anchor apple generic and certificate leaf[subject.OU] = \"ABCDE12345\""
        );
        assert_eq!(mac::requirement(Some("bad\" or 1")), "identifier \"app.meowpuut.desk\"");
    }
}
