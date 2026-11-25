﻿# 把 start-proxy.bat 放进开机启动项，重启后代理自动运行并自动上报本机 IP。
# 用法：右键「使用 PowerShell 运行」
$ErrorActionPreference = 'Stop'

$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$bat = Join-Path $dir 'start-proxy.bat'
if (-not (Test-Path $bat)) {
  Write-Host "[ERROR] start-proxy.bat not found in $dir" -ForegroundColor Red
  exit 1
}

$codeFile = Join-Path $dir 'terminal-code.txt'
if (-not (Test-Path $codeFile)) {
  Write-Host "[WARN] terminal-code.txt missing. Write this PC's terminal code (e.g. INTERN01XJH2)." -ForegroundColor Yellow
} elseif (-not (Get-Content $codeFile -Raw).Trim()) {
  Write-Host "[WARN] terminal-code.txt is empty. Write this PC's terminal code (e.g. INTERN01XJH2)." -ForegroundColor Yellow
}

$startup = [Environment]::GetFolderPath('Startup')
$lnk = Join-Path $startup 'DYA-CDP-proxy.lnk'
$ws = New-Object -ComObject WScript.Shell
$s = $ws.CreateShortcut($lnk)
$s.TargetPath = $bat
$s.WorkingDirectory = $dir
$s.WindowStyle = 7
$s.Description = 'DYA collector terminal: CDP proxy + IP announce'
$s.Save()

Write-Host "[OK] autostart installed: $lnk"
Write-Host "Now double-click start-proxy.bat once and check the log line 'report ... success'."
Start-Sleep -Seconds 3
