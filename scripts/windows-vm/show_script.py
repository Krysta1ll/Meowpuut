# Writes the PowerShell script that starts the preview in the signed-in
# desktop session with the given arguments.
#   python3 show_script.py <out.ps1> <preview arguments…>
import sys

def quote(arg):
    # A Windows command-line argument inside a PowerShell single-quoted string.
    return "'" + ('"' + arg.replace('"', '\\"') + '"').replace("'", "''") + "'"

line = ' + " " + '.join(quote(a) for a in sys.argv[2:])
script = f'''Get-Process MeowpuutCandidatePreview -ErrorAction SilentlyContinue | Stop-Process -Force
$a = New-ScheduledTaskAction -Execute 'C:\\meow\\preview\\MeowpuutCandidatePreview.exe' -Argument ({line})
$p = New-ScheduledTaskPrincipal -UserId meow -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName MeowpuutShow -Action $a -Principal $p -Force | Out-Null
Start-ScheduledTask -TaskName MeowpuutShow
Start-Sleep -Seconds 3
if (-not (Get-Process MeowpuutCandidatePreview -ErrorAction SilentlyContinue)) {{ Write-Error 'preview did not start'; exit 1 }}
'''
# UTF-8 with BOM: Windows PowerShell 5 reads files without one in the ANSI code page.
open(sys.argv[1], 'w', encoding='utf-8-sig', newline='\r\n').write(script)
