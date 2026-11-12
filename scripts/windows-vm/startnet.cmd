@echo off
rem Windows PE start script (replaced in boot.wim by scripts/windows-vm.sh).
wpeinit
for %%d in (C D E F G H I J K L M N) do if exist %%d:\meow\deploy.cmd call %%d:\meow\deploy.cmd %%d:
