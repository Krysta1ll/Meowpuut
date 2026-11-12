# Windows VM handoff

- Branch has lexicon-without-rime-ice merged (incl. 95ade04 cats). Preview has a screenshot mode (`--at`, top of Windows/Preview/PreviewApp.cpp); build-windows-check.sh passes.
- scripts/windows-vm.sh + scripts/windows-vm/ (deploy.cmd, unattend.xml, firstlogon.ps1, startnet.cmd, qmp.py, show_script.py, type.py for typing into the VM console).
- Files: /Users/scarletkrimilia/Documents/Projects/Meowpuut/.build/windows-vm/ (media/windows.esd zh-TW Arm64 from Microsoft's MCT catalog, SHA-1 checked; install.iso = WinPE; data.dmg = exFAT with ESD, netkvm, OpenSSH, answer file).
- Done: WinPE boots, finds data.dmg, diskpart + dism apply runs.
- In progress: `bash scripts/windows-vm.sh create` (log: .build/windows-vm/logs/create.log). If interrupted: kill qemu, delete disk.qcow2 and run create again (if `installed` exists, only the first boot runs).
- Next: first boot/OOBE -> SSH; deploy; run layout test; shots at 100 % and 200 %; README findings.
