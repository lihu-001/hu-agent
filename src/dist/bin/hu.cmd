@echo off
rem hu-agent launcher. Layout: <HU_HOME>\app\bin\hu.cmd, Java runtime is always <HU_HOME>\runtime.
rem HU_JAVA overrides java.exe (development only); HU_JAVA_OPTS appends JVM options.
rem Keep this file ASCII-only: cmd.exe parses batch files in the console code page.
setlocal
for %%I in ("%~dp0..") do set "HU_APP=%%~fI"
for %%I in ("%~dp0..\..") do set "HU_ROOT=%%~fI"

rem hu update: reinstall with install.ps1. No option: the latest release (installer from GitHub).
rem --local: build the source tree in the current directory; --pkg: a local hu-agent.zip or a directory holding one.
rem Both pass the package through HU_PACKAGE; --pkg uses the installer shipped in app\, so it works offline.
set "HU_UPDATE="
if /i "%~1"=="update" if "%~2"=="" set "HU_UPDATE=release"
if /i "%~1"=="update" if /i "%~2"=="--local" if "%~3"=="" set "HU_UPDATE=local"
if /i "%~1"=="update" if /i "%~2"=="--pkg" if not "%~3"=="" if "%~4"=="" set "HU_UPDATE=pkg"
if /i "%~1"=="update" if not defined HU_UPDATE (
    echo hu: usage: hu update [--local ^| --pkg ^<package^>] 1>&2
    exit /b 2
)
if "%HU_UPDATE%"=="local" if not exist "src\dist\bin\hu.cmd" (
    echo hu: run "hu update --local" in the hu-agent source directory. 1>&2
    exit /b 2
)
if "%HU_UPDATE%"=="local" set "HU_PACKAGE=%CD%\target\hu-agent.zip"
if "%HU_UPDATE%"=="pkg" if not exist "%HU_APP%\install.ps1" (
    echo hu: "%HU_APP%\install.ps1" not found. Re-run the installer once to get it. 1>&2
    exit /b 1
)
if "%HU_UPDATE%"=="pkg" set "HU_PACKAGE=%~3"
if defined HU_UPDATE set "HU_HOME=%HU_ROOT%"
if defined HU_UPDATE setlocal EnableDelayedExpansion
rem The installer replaces this file while it runs and cmd reads batch files line by line, so each branch is
rem one parenthesized block (parsed before it runs) ending in exit. Only a plain "exit /b" directly in a
rem top-level block reaches the caller as the exit code (not one behind "||" or inside a nested "if").
if "%HU_UPDATE%"=="release" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; irm https://raw.githubusercontent.com/lihu-001/hu-agent/main/install.ps1 | iex"
    exit /b !ERRORLEVEL!
)
if "%HU_UPDATE%"=="local" (
    echo hu: building the source in the current directory, tests skipped...
    call .\mvnw.cmd -q package -DskipTests && powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1
    exit /b !ERRORLEVEL!
)
if "%HU_UPDATE%"=="pkg" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "& (Join-Path $env:HU_APP 'install.ps1')"
    exit /b !ERRORLEVEL!
)

set "HU_JAVA_CMD=%HU_ROOT%\runtime\bin\java.exe"
if defined HU_JAVA set "HU_JAVA_CMD=%HU_JAVA%"
if not exist "%HU_JAVA_CMD%" (
    echo hu: Java runtime not found: "%HU_JAVA_CMD%". Please re-run the installer. 1>&2
    exit /b 1
)
rem fastjson2 still uses sun.misc.Unsafe; since JDK 24 that prints a warning on every start unless allowed.
"%HU_JAVA_CMD%" -Dfile.encoding=UTF-8 "-Dhu.home=%HU_ROOT%" -XX:+UseSerialGC -XX:TieredStopAtLevel=1 --sun-misc-unsafe-memory-access=allow %HU_JAVA_OPTS% -jar "%HU_APP%\lib\hu-agent.jar" %*
exit /b %ERRORLEVEL%
