# Windows VM handoff

- Works: `bash scripts/windows-vm.sh start|stop|run|copy|deploy|show|shots|scale|shot|desktop|type|keys|click|snapshot`. See Windows/README.md, section 在 Mac 上的 Windows 虛擬機.
- VM files: /Users/scarletkrimilia/Documents/Projects/Meowpuut/.build/windows-vm/ (disk.qcow2 16 GB with internal snapshot `clean` at 100 % scale, vars.fd + vars-clean.fd, ssh/, media/ 11 GB). VM is left running.
- Proven: layout test passes on Windows; 24 screenshots in .build/windows-vm-shots/ (100 % and 150 %); candidate click commits, hover highlights, cat clicks land on the window.
- Not done: 200 % (screen 1024 x 768 too small); a full `create` from nothing with the final deploy.cmd (the run that built this VM finished the last WinPE steps by typed commands after the DISM driver step failed; deploy.cmd now has the fix but was not rerun end to end). media/data.dmg was deleted so the next create rebuilds it.
