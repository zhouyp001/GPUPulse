@echo off
rem Start central server (pure Netty). Ensures deps then runs from repo root.
rem Optional overrides via JAVA_TOOL_OPTIONS, e.g. -Dserver.port=9090
cd /d "%~dp0\.."
call scripts\copy-libs.bat >nul
java -Dfile.encoding=UTF-8 -jar server\target\monitor-server.jar %*
