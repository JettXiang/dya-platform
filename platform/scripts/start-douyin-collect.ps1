$ErrorActionPreference = 'SilentlyContinue'

$chrome = 'C:\Users\dataeye\AppData\Local\Google\Chrome\Application\chrome.exe'
if (-not (Test-Path $chrome)) { $chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe' }
# Chrome 136+ forbids --remote-debugging-port on the default profile, so use a dedicated one.
$profile = Join-Path $env:USERPROFILE 'dy-collect-profile'
$node = 'C:\Program Files\nodejs\node.exe'
$bridge = Join-Path $PSScriptRoot 'cdp-bridge.cjs'

$bridgeUp = $false
try {
  $r = Invoke-WebRequest -Uri 'http://127.0.0.1:9333/ping' -UseBasicParsing -TimeoutSec 2
  $bridgeUp = ($r.StatusCode -eq 200)
} catch { }

if (-not $bridgeUp -and (Test-Path $node) -and (Test-Path $bridge)) {
  $cmd = '"' + $node + '" "' + $bridge + '"'
  Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd } | Out-Null
}

$cdpUp = $false
try {
  $r = Invoke-WebRequest -Uri 'http://127.0.0.1:9222/json/version' -UseBasicParsing -TimeoutSec 2
  $cdpUp = ($r.StatusCode -eq 200)
} catch { }

if (-not $cdpUp) {
  if (-not (Test-Path $chrome)) {
    Write-Host "[ERROR] Chrome not found: $chrome"
    exit 1
  }
  $busy = Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" |
          Where-Object { $_.CommandLine -like '*dy-collect-profile*' }
  if ($busy) {
    Write-Host '[WARN] A collect window is already open without CDP. Close that window, then run this shortcut again.'
    exit 2
  }
  New-Item -ItemType Directory -Force -Path $profile | Out-Null
  $cmd = '"' + $chrome + '" --remote-debugging-port=9222' +
         ' --remote-allow-origins=http://127.0.0.1:19203,http://localhost:19203' +
         ' --user-data-dir="' + $profile + '"' +
         ' --window-size=1920,1080 --start-maximized' +
         ' --restore-last-session --no-first-run --no-default-browser-check' +
         ' https://www.douyin.com/user/self'
  Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd } | Out-Null

  for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    try {
      $r = Invoke-WebRequest -Uri 'http://127.0.0.1:9222/json/version' -UseBasicParsing -TimeoutSec 2
      if ($r.StatusCode -eq 200) { $cdpUp = $true; break }
    } catch { }
  }
}

if ($cdpUp) {
  Write-Host '[OK] collect window ready: CDP 9222 + bridge 9333'
  exit 0
}
Write-Host '[WARN] 9222 not listening. Close old collect window (profile lock) and retry.'
exit 1
