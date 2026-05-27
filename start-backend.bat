@echo off
setlocal

:restart
if exist "C:\Claude\mannschaft\.stop-backend" (
    del "C:\Claude\mannschaft\.stop-backend" 2>nul
    echo [%date% %time%] 停止フラグを検出。Spring Boot の自動再起動を停止します。
    exit /b 0
)

cd /d C:\Claude\mannschaft\backend
echo [%date% %time%] Spring Boot を起動します...
call gradlew.bat bootRun --no-daemon
echo [%date% %time%] Spring Boot が終了しました（終了コード: %ERRORLEVEL%）。

if exist "C:\Claude\mannschaft\.stop-backend" (
    del "C:\Claude\mannschaft\.stop-backend" 2>nul
    echo [%date% %time%] 停止フラグを検出。自動再起動を停止します。
    exit /b 0
)

echo [%date% %time%] 5秒後に自動再起動します...
timeout /t 5 /nobreak >nul
goto restart
