//! Development only (feature `devcapture`, macOS): the window photographs its own page and quits.
//!
//!   MEOWPUUT_CAPTURE=/tmp/home.png [MEOWPUUT_CAPTURE_DELAY_MS=2500] target/debug/meowpuut-companion
//!
//! It asks the WKWebView for a snapshot (`takeSnapshotWithConfiguration:completionHandler:`), so it needs no
//! screen-recording permission and shows exactly the page (not the system's title bar). The PNG is at the
//! display's backing scale.

use block2::RcBlock;
use objc2_app_kit::{NSBitmapImageFileType, NSBitmapImageRep, NSImage};
use objc2_foundation::{NSDictionary, NSError};
use objc2_web_kit::WKWebView;
use std::time::Duration;
use tauri::{AppHandle, Manager};

pub fn schedule(app: &AppHandle) {
    let Ok(path) = std::env::var("MEOWPUUT_CAPTURE") else { return };
    let delay = std::env::var("MEOWPUUT_CAPTURE_DELAY_MS").ok().and_then(|v| v.parse().ok()).unwrap_or(2_500);
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(delay));
        let Some(window) = app.get_webview_window("main") else { return };
        let handle = app.clone();
        let _ = window.with_webview(move |webview| {
            // SAFETY: on macOS `inner()` is the WKWebView this window owns, alive for the duration of the call.
            let view: &WKWebView = unsafe { &*webview.inner().cast::<WKWebView>() };
            let block = RcBlock::new(move |image: *mut NSImage, _error: *mut NSError| {
                // SAFETY: WebKit passes a valid image or NULL.
                if let Some(image) = unsafe { image.as_ref() } {
                    let png = image.TIFFRepresentation().and_then(|tiff| NSBitmapImageRep::imageRepWithData(&tiff)).and_then(|rep| {
                        // SAFETY: an empty dictionary is a valid properties argument.
                        unsafe { rep.representationUsingType_properties(NSBitmapImageFileType::PNG, &NSDictionary::new()) }
                    });
                    match png {
                        Some(data) => {
                            if let Err(error) = std::fs::write(&path, data.to_vec()) {
                                eprintln!("devcapture: {error}");
                            }
                        }
                        None => eprintln!("devcapture: no PNG"),
                    }
                }
                handle.exit(0);
            });
            // SAFETY: called on the main thread (with_webview), with a block WebKit retains until it calls it.
            unsafe { view.takeSnapshotWithConfiguration_completionHandler(None, &block) };
        });
    });
}
