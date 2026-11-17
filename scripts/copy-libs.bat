@echo off
rem Copy runtime dependency jars into each module's target\lib for java -jar Class-Path.
rem Offline env: jackson lives in D:\workspace\addRespos, netty in D:\workspace\localRepository.
rem Override with env vars M2_ADD and M2_LOCAL if paths differ.
setlocal
cd /d "%~dp0\.."

set "M2A=D:\workspace\addRespos"
set "M2L=D:\workspace\localRepository"
if defined M2_ADD set "M2A=%M2_ADD%"
if defined M2_LOCAL set "M2L=%M2_LOCAL%"

set "COMMON_JAR=common\target\monitor-common-0.1.0-SNAPSHOT.jar"

if not exist server\target\lib mkdir server\target\lib
if not exist agent\target\lib mkdir agent\target\lib

copy /y "%COMMON_JAR%" server\target\lib\ >nul
copy /y "%COMMON_JAR%" agent\target\lib\ >nul

for %%A in (jackson-databind jackson-annotations jackson-core) do (
  copy /y "%M2A%\com\fasterxml\jackson\core\%%A\2.17.3\%%A-2.17.3.jar" server\target\lib\ >nul
  copy /y "%M2A%\com\fasterxml\jackson\core\%%A\2.17.3\%%A-2.17.3.jar" agent\target\lib\ >nul
)

for %%A in (netty-buffer netty-common netty-transport netty-resolver netty-codec netty-codec-http netty-handler) do (
  copy /y "%M2L%\io\netty\%%A\4.1.76.Final\%%A-4.1.76.Final.jar" server\target\lib\ >nul
)

echo Dependency libs ready: server\target\lib / agent\target\lib
endlocal
