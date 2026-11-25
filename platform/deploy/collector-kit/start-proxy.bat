@echo off
rem CDP proxy: 0.0.0.0:9223 -> 127.0.0.1:9222, and report this PC's IP to the platform
rem Set the terminal code in terminal-code.txt in the same folder (e.g. INTERN01XJH2)
rem Use node.exe in this folder if present, otherwise fall back to PATH.
setlocal
set "DIR=%~dp0"
if exist "%DIR%node.exe" (
  set "NODE=%DIR%node.exe"
) else (
  where node >nul 2>nul
  if errorlevel 1 (
    echo [ERROR] node not found. Put node.exe in this folder or install Node.js.
    pause
    exit /b 1
  )
  set "NODE=node"
)
start "" /min "%NODE%" "%DIR%cdp-proxy.js"
