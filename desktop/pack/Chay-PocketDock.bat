@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo PocketDock: dang bat may chu va duong ham HTTPS cho dien thoai.
echo Lan dau chay co the mat vai phut de tai cloudflared. Can co Internet.
echo.
"%~dp0PocketDock.exe" --auto
echo.
echo PocketDock da dong. Nhan phim bat ky de thoat.
pause >nul
