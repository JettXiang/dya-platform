@echo off
rem 保持 WSL 发行版常驻，避免发行版休眠导致平台容器被重启、127.0.0.1:19203 断连
wsl.exe -d Ubuntu-24.04 -- bash -lc "cd '/mnt/c/Users/dataeye/AppData/Roaming/Trae CN/ModularData/ai-agent/work-mode-projects/6aac99a556e079edbea957d7/platform/deploy' && for i in $(seq 1 20); do docker info >/dev/null 2>&1 && break; sleep 3; done; docker compose up -d; exec sleep infinity"
