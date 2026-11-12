# First sign-in of the Meowpuut Windows VM (FirstLogonCommands in
# unattend.xml): OpenSSH server, permanent automatic sign-in, no sleep, no
# automatic updates.
$ErrorActionPreference = 'Continue'
Start-Transcript C:\meow\firstlogon.log

$winlogon = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon'
Set-ItemProperty $winlogon AutoAdminLogon '1'
Set-ItemProperty $winlogon DefaultUserName 'meow'
Set-ItemProperty $winlogon DefaultPassword 'meow'
Remove-ItemProperty $winlogon AutoLogonCount -ErrorAction SilentlyContinue

# Test VM only: administrators are elevated without a UAC prompt, so install
# scripts run unattended. UAC itself stays on (Store apps need it).
$policies = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System'
Set-ItemProperty $policies ConsentPromptBehaviorAdmin 0 -Type DWord
Set-ItemProperty $policies PromptOnSecureDesktop 0 -Type DWord

powercfg /change monitor-timeout-ac 0
powercfg /change standby-timeout-ac 0
powercfg /hibernate off
Set-ItemProperty 'HKCU:\Control Panel\Desktop' ScreenSaveActive '0'
New-Item 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU' -Force | Out-Null
Set-ItemProperty 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU' NoAutoUpdate 1 -Type DWord
New-Item 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\Personalization' -Force | Out-Null
Set-ItemProperty 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\Personalization' NoLockScreen 1 -Type DWord
New-Item 'HKCU:\Software\Microsoft\Windows\CurrentVersion\UserProfileEngagement' -Force | Out-Null
Set-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\UserProfileEngagement' ScoobeSystemSettingEnabled 0 -Type DWord

# Microsoft's OpenSSH for Windows (PowerShell/Win32-OpenSSH), key sign-in only.
Expand-Archive C:\meow\OpenSSH-ARM64.zip 'C:\Program Files' -Force
Rename-Item 'C:\Program Files\OpenSSH-ARM64' 'C:\Program Files\OpenSSH'
& 'C:\Program Files\OpenSSH\install-sshd.ps1'
New-Item C:\ProgramData\ssh -ItemType Directory -Force | Out-Null
@'
AuthorizedKeysFile .ssh/authorized_keys
PasswordAuthentication no
PubkeyAuthentication yes
Subsystem sftp sftp-server.exe
'@ | Set-Content -Encoding ascii C:\ProgramData\ssh\sshd_config
New-Item C:\Users\meow\.ssh -ItemType Directory -Force | Out-Null
Copy-Item C:\meow\authorized_keys C:\Users\meow\.ssh\authorized_keys
New-NetFirewallRule -Name sshd -DisplayName 'OpenSSH Server' -Protocol TCP -LocalPort 22 -Action Allow | Out-Null
Set-Service sshd -StartupType Automatic
Start-Service sshd

Stop-Transcript
