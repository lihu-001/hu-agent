@echo off
rem hu-agent launcher. Layout: <HU_HOME>\app\bin\hu.cmd, Java runtime is always <HU_HOME>\runtime.
rem HU_JAVA overrides java.exe (development only); HU_JAVA_OPTS appends JVM options.
rem Keep this file ASCII-only: cmd.exe parses batch files in the console code page.
setlocal
for %%I in ("%~dp0..") do set "HU_APP=%%~fI"
for %%I in ("%~dp0..\..") do set "HU_ROOT=%%~fI"
rem hu update: reinstall with install.ps1 (latest release, or --local: the source tree in the current directory).
rem The installer replaces this file while it runs and cmd reads batch files line by line, so the whole branch
rem is one parenthesized block (parsed before it runs) and every path ends with exit.
if /i "%~1"=="update" (
    set "HU_HOME=%HU_ROOT%"
    if "%~2"=="" (
        powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; irm https://raw.githubusercontent.com/lihu-001/hu-agent/main/install.ps1 | iex" || exit /b 1
        exit /b 0
    )
    if /i "%~2"=="--local" if "%~3"=="" (
        if not exist "%CD%\src\dist\bin\hu.cmd" (
            echo hu: run "hu update --local" in the hu-agent source directory. 1>&2
            exit /b 2
        )
        call "%CD%\mvnw.cmd" -q package -DskipTests || exit /b 1
        powershell -NoProfile -ExecutionPolicy Bypass -Command "$t = (Resolve-Path -LiteralPath 'target').ProviderPath; $zip = Join-Path $t 'hu-agent.zip'; (Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLowerInvariant() | Set-Content -LiteralPath ($zip + '.sha256') -Encoding ASCII; $env:HU_DOWNLOAD_BASE = ([Uri] $t).AbsoluteUri; $env:HU_VERSION = 'local'; & (Join-Path (Get-Location) 'install.ps1')" || exit /b 1
        exit /b 0
    )
    echo hu: usage: hu update [--local] 1>&2
    exit /b 2
)
set "HU_JAVA_CMD=%HU_ROOT%\runtime\bin\java.exe"
if defined HU_JAVA set "HU_JAVA_CMD=%HU_JAVA%"
if not exist "%HU_JAVA_CMD%" (
    echo hu: Java runtime not found: "%HU_JAVA_CMD%". Please re-run the installer. 1>&2
    exit /b 1
)
"%HU_JAVA_CMD%" -Dfile.encoding=UTF-8 "-Dhu.home=%HU_ROOT%" -XX:+UseSerialGC -XX:TieredStopAtLevel=1 %HU_JAVA_OPTS% -jar "%HU_APP%\lib\hu-agent.jar" %*
exit /b %ERRORLEVEL%
