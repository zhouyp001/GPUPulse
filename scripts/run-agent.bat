@echo off
rem Start agent: collect local GPU/custom metrics and report to central server.
rem Use JAVA_TOOL_OPTIONS for overrides, e.g. -Dagent.server.id=web01, -Dagent.simulate=true
cd /d "%~dp0\.."
call scripts\copy-libs.bat >nul
java -Dfile.encoding=UTF-8 -jar agent\target\monitor-agent.jar %*
