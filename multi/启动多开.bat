@echo off
chcp 65001 >nul
title 自动采矿助手
echo ============================================
echo   自动采矿助手  ·  一键多开 + 控制台
echo ============================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0一键多开.ps1" %*
echo.
echo --------------------------------------------
echo   进入控制台（quit 退出，机器人不受影响）
echo --------------------------------------------
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0控制台.ps1"
pause
