# Windows VM handoff

- Branch merged with lexicon-without-rime-ice. Preview has a screenshot mode (`--at`, see top of Windows/Preview/PreviewApp.cpp); cross build passes.
- Installation image: zh-TW Arm64 ESD from Microsoft's Media Creation Tool catalog (products.cab, go.microsoft.com/fwlink/?LinkId=2156292), downloading to /Users/scarletkrimilia/Documents/Projects/Meowpuut/.build/windows-vm/media/.
- Homebrew: qemu, mingw-w64, wimlib, xorriso.
- Next: scripts/windows-vm.sh (create/start/stop/run/copy/shot), unattended install, screenshots.
