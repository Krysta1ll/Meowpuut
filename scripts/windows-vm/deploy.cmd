@echo off
rem Runs in Windows PE from install.iso: partitions the NVMe disk, applies
rem Windows 11 Pro, adds the network driver and the first-sign-in files,
rem makes it bootable and powers off. %1 is the ISO's drive, e.g. D:
set M=%1
(
echo select disk 0
echo clean
echo convert gpt
echo create partition efi size=260
echo format quick fs=fat32 label=System
echo assign letter=S
echo create partition msr size=16
echo create partition primary
echo format quick fs=ntfs label=Windows
echo assign letter=W
) > X:\disk.txt
diskpart /s X:\disk.txt || goto fail
dism /Apply-Image /ImageFile:%M%\sources\install.esd /Index:1 /ApplyDir:W:\ || goto fail
if exist %M%\meow\drivers\netkvm dism /Image:W:\ /Add-Driver /Driver:%M%\meow\drivers /Recurse || goto fail
mkdir W:\Windows\Panther W:\meow
copy %M%\meow\unattend.xml W:\Windows\Panther\unattend.xml
copy %M%\meow\firstlogon.ps1 W:\meow\
copy %M%\meow\OpenSSH-ARM64.zip W:\meow\
copy %M%\meow\authorized_keys W:\meow\
bcdboot W:\Windows /s S: /f UEFI || goto fail
rem Use the firmware's largest screen mode instead of 1024 x 768.
bcdedit /store S:\EFI\Microsoft\Boot\BCD /set {globalsettings} highestmode on
wpeutil shutdown
:fail
echo deploy failed
cmd /k
