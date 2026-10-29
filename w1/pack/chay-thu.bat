@echo off
cd /d "%~dp0"
if not exist ketqua mkdir ketqua

echo ==============================================
echo   POCKETDOCK - BO THU NHO TREN WINDOWS (W1)
echo ==============================================
echo.
echo Thu tong cong khoang 2-3 phut. Khong can quyen quan tri.
echo.

echo [0/3] Thu thap thong tin may...
powershell -NoProfile -Command "'He dieu hanh: ' + (Get-CimInstance Win32_OperatingSystem).Caption + ' ' + (Get-CimInstance Win32_OperatingSystem).Version; 'CPU: ' + (Get-CimInstance Win32_Processor).Name; 'RAM (GB): ' + [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1); 'Card man hinh:'; Get-CimInstance Win32_VideoController | ForEach-Object { '  ' + $_.Name + ' (driver ' + $_.DriverVersion + ')' }; 'Am thanh:'; Get-CimInstance Win32_SoundDevice | ForEach-Object { '  ' + $_.Name }" > ketqua\thong-tin-may.txt
type ketqua\thong-tin-may.txt
echo.

echo [1/3] THU CHUP MAN HINH (khoang 30 giay)
echo Hay de man hinh dang hien mot noi dung bat ky (khong de man hinh den).
pause
ScreenProbe\ScreenProbe.exe
echo.

echo [2/3] THU CHUOT
echo Chuot se tu di chuyen va nhap. Trong khoang 15 giay KHONG cham vao chuot va ban phim.
pause
ScreenProbe\MouseProbe.exe
echo.

echo [3/3] THU AM THANH MAY
echo HAY MO NHAC hoac mot video co tieng tren may nay (bat to vua phai), roi nhan phim bat ky.
pause
AudioProbe\AudioProbe.exe 10
echo.

echo ==============================================
echo XONG. Hay chep toan bo thu muc "ketqua" (co cac file .txt va loopback.wav)
echo ve USB hoac Google Drive de mang ve phan tich.
echo ==============================================
start "" ketqua
pause
