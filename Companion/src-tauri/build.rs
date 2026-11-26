fn main() {
    steam_redistributable();
    tauri_build::build()
}

/// With the `steam` feature: steamworks-rs links `libsteam_api.dylib` / `steam_api64.dll` dynamically but does not put
/// it anywhere the binary can find it at run time (its install name is `@loader_path/libsteam_api.dylib`, so it must
/// sit next to the executable). This copies Valve's redistributable for the target
///
/// - next to the binary in `target/<profile>/` (so `cargo run` and `tauri dev` start), and
/// - into `steam-redist/` beside this file (git-ignored), where `tauri.steam-macos.conf.json` and
///   `tauri.steam-windows.conf.json` bundle it from: the Mac bundle's `Contents/MacOS/`, the Windows install folder.
///
/// The source is `STEAM_SDK_LOCATION` (the Steamworks SDK's `sdk/` folder, as steamworks-sys itself reads it; use the
/// SDK downloaded from partner.steamgames.com for release builds), otherwise the copy of SDK 1.64's redistributable
/// that ships inside the steamworks-sys crate in cargo's registry.
fn steam_redistributable() {
    use std::path::{Path, PathBuf};
    println!("cargo:rerun-if-env-changed=STEAM_SDK_LOCATION");
    if std::env::var_os("CARGO_FEATURE_STEAM").is_none() {
        return;
    }
    let target = std::env::var("TARGET").unwrap_or_default();
    let (folder, file) = if target.contains("windows") {
        if target.contains("i686") {
            ("", "steam_api.dll")
        } else {
            ("win64", "steam_api64.dll")
        }
    } else if target.contains("darwin") {
        ("osx", "libsteam_api.dylib")
    } else {
        ("linux64", "libsteam_api.so")
    };
    let sdk_dirs: Vec<PathBuf> = match std::env::var_os("STEAM_SDK_LOCATION") {
        Some(sdk) => vec![PathBuf::from(sdk)],
        None => {
            let home = std::env::var_os("CARGO_HOME")
                .map(PathBuf::from)
                .or_else(|| std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")).map(|h| PathBuf::from(h).join(".cargo")));
            let mut found = Vec::new();
            if let Some(Ok(registries)) = home.map(|h| std::fs::read_dir(h.join("registry").join("src"))) {
                for registry in registries.flatten() {
                    if let Ok(crates) = std::fs::read_dir(registry.path()) {
                        for c in crates.flatten() {
                            if c.file_name().to_string_lossy().starts_with("steamworks-sys-0.13.") {
                                found.push(c.path().join("lib").join("steam"));
                            }
                        }
                    }
                }
            }
            found.sort();
            found.reverse();
            found
        }
    };
    let Some(source) = sdk_dirs.iter().map(|d| d.join("redistributable_bin").join(folder).join(file)).find(|p| p.is_file()) else {
        println!("cargo:warning=steam: Valve's {file} not found; set STEAM_SDK_LOCATION to the Steamworks SDK's sdk/ folder");
        return;
    };
    println!("cargo:rerun-if-changed={}", source.display());
    let copy = |dir: &Path| {
        if std::fs::create_dir_all(dir).is_ok() {
            let _ = std::fs::copy(&source, dir.join(file));
        }
    };
    // OUT_DIR is target/<profile>/build/<crate>-<hash>/out: three levels up is the folder holding the binary.
    if let Some(profile_dir) = std::env::var_os("OUT_DIR").map(PathBuf::from).and_then(|o| o.ancestors().nth(3).map(Path::to_path_buf)) {
        copy(&profile_dir);
    }
    if let Some(manifest) = std::env::var_os("CARGO_MANIFEST_DIR") {
        copy(&PathBuf::from(manifest).join("steam-redist"));
    }
}
