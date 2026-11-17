@echo off
rem Build self-contained dist\agent and dist\server deployable packages.
rem Usage: scripts\package.bat
setlocal
cd /d "%~dp0\.."

echo == Build modules (mvn package)
call mvn -q -DskipTests package
if errorlevel 1 goto :err

echo == Copy deps to target\lib
call scripts\copy-libs.bat >nul

rmdir /s /q dist 2>nul
mkdir dist\agent dist\server

echo == Assemble agent package
copy /y agent\target\monitor-agent.jar dist\agent\ >nul
xcopy /e /i /q agent\target\lib dist\agent\lib\ >nul
copy /y scripts\daemon\start.sh dist\agent\ >nul
copy /y scripts\daemon\stop.sh dist\agent\ >nul
copy /y scripts\daemon\status.sh dist\agent\ >nul

echo == Assemble server package
copy /y server\target\monitor-server.jar dist\server\ >nul
xcopy /e /i /q server\target\lib dist\server\lib\ >nul
xcopy /e /i /q web dist\server\web\ >nul
copy /y scripts\daemon\start.sh dist\server\ >nul
copy /y scripts\daemon\stop.sh dist\server\ >nul
copy /y scripts\daemon\status.sh dist\server\ >nul

copy /y scripts\DEPLOY.md dist\README.md >nul

echo.
echo Done:
echo   dist\agent    - deploy to each GPU server, run ./start.sh
echo   dist\server   - deploy to monitor machine, run ./start.sh
echo   See dist\README.md
goto :eof

:err
echo Build failed.
exit /b 1
