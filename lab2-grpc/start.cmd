@echo off
rem Double-click to start the whole system. Passes any arguments through (e.g. -SkipBuild, -Stop).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
