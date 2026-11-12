# Writes a PowerShell script that starts a program in the signed-in user's
# desktop session through a scheduled task (programs started over SSH run in
# session 0 and their windows are invisible).
#   python3 show_script.py <out.ps1> <program> [<argument>…]   arguments quoted one by one
#   python3 show_script.py <out.ps1> --line '<command line>'   passed to cmd.exe /c start as written
import sys

def quote(arg):
    # One Windows command-line argument, inside a PowerShell single-quoted string.
    return "'" + ('"' + arg.replace('"', '\\"') + '"').replace("'", "''") + "'"

def single(text):
    return "'" + text.replace("'", "''") + "'"

if sys.argv[2] == '--line':
    program = 'cmd.exe'
    arguments = single('/c start "" ' + sys.argv[3])
else:
    program = sys.argv[2]
    arguments = ' + " " + '.join(quote(a) for a in sys.argv[3:]) or "''"
script = f'''$a = New-ScheduledTaskAction -Execute {single(program)} -Argument ({arguments})
$p = New-ScheduledTaskPrincipal -UserId meow -LogonType Interactive -RunLevel Highest
$s = New-ScheduledTaskSettingsSet -ExecutionTimeLimit 0 -AllowStartIfOnBatteries
Register-ScheduledTask -TaskName MeowpuutDesktop -Action $a -Principal $p -Settings $s -Force | Out-Null
Start-ScheduledTask -TaskName MeowpuutDesktop
Start-Sleep -Seconds 2
'''
# UTF-8 with BOM: Windows PowerShell 5 reads files without one in the ANSI code page.
open(sys.argv[1], 'w', encoding='utf-8-sig', newline='\r\n').write(script)
