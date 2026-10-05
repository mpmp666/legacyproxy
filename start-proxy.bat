@echo off
REM LegacyProxy - MCPE 0.14.3 <-> modern Bedrock (1.26.50) protocol bridge.
REM 0.14.3 clients connect to the listen port; the proxy logs into the backend for them.
cd /d "%~dp0"

set CP=build\libs\legacyproxy-1.0.0.jar;libs\Nukkit-MOT-SNAPSHOT.jar
if not exist "libs\Nukkit-MOT-SNAPSHOT.jar" (
    echo [!] libs\Nukkit-MOT-SNAPSHOT.jar is missing - see README ^(Build^).
    pause
    exit /b 1
)

echo [start] LegacyProxy  ^(config: proxy.properties^)
java -cp "%CP%" proxy.ProxyMain
pause
