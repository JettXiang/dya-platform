@echo off
chcp 65001 >nul
title 抖音采集窗口（CDP 9222 / 桥 9333）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-douyin-collect.ps1"
exit /b %errorlevel%
