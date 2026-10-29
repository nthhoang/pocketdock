@echo off
cd /d "%~dp0"
if not exist ketqua mkdir ketqua

echo ==============================================
echo   POCKETDOCK - CHAY LAI BAI THU CHUP MAN HINH
echo ==============================================
echo.
echo Bai nay mat khoang 1-2 phut. Hay de man hinh dang hien mot noi dung bat ky,
echo khong de man hinh den, va KHONG dong cua so nay.
echo.
pause

powershell -NoProfile -Command "'He dieu hanh: ' + (Get-CimInstance Win32_OperatingSystem).Caption + ' ' + (Get-CimInstance Win32_OperatingSystem).Version; 'CPU: ' + (Get-CimInstance Win32_Processor).Name; 'RAM (GB): ' + [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1); 'Card man hinh:'; Get-CimInstance Win32_VideoController | ForEach-Object { '  ' + $_.Name + ' (driver ' + $_.DriverVersion + ')' }" > ketqua\thong-tin-may-lan2.txt
type ketqua\thong-tin-may-lan2.txt
echo.

ScreenProbe\ScreenProbe.exe
echo.
echo ==============================================
echo XONG. Hay chep thu muc "ketqua" ve.
echo ==============================================
start "" ketqua
pause
