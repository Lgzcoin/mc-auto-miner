@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0更新工具.ps1"
if errorlevel 1 (
  echo.
  echo 更新没有完成，看看上面的提示。
  pause
)
