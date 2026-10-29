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
rem By default only the status is shown, and a failure prints one line with the reason; --debug / -d (before
rem the source option) shows the full build and installer output.
set "HU_UPDATE="
set "HU_UPDATE_DEBUG="
if /i "%~1"=="update" if /i "%~2"=="--debug" set "HU_UPDATE_DEBUG=1"
if /i "%~1"=="update" if /i "%~2"=="-d" set "HU_UPDATE_DEBUG=1"
if defined HU_UPDATE_DEBUG shift /2
if /i "%~1"=="update" if "%~2"=="" set "HU_UPDATE=release"
if /i "%~1"=="update" if /i "%~2"=="--local" if "%~3"=="" set "HU_UPDATE=local"
if /i "%~1"=="update" if /i "%~2"=="--pkg" if not "%~3"=="" if "%~4"=="" set "HU_UPDATE=pkg"
if /i "%~1"=="update" if not defined HU_UPDATE (
    echo hu: usage: hu update [--debug ^| -d] [--local ^| --pkg ^<package^>] 1>&2
    exit /b 2
)
if "%HU_UPDATE%"=="local" set "HU_PACKAGE=%CD%\target\hu-agent.zip"
if "%HU_UPDATE%"=="pkg" set "HU_PACKAGE=%~3"
if defined HU_UPDATE set "HU_HOME=%HU_ROOT%"
if defined HU_UPDATE setlocal EnableDelayedExpansion
rem Parse the entire execution block before the installer replaces this launcher.
rem Keep exit /b directly in this block so the caller receives the actual exit code.
rem The build and the installer each run as a child process, and quiet mode drops their output at the process
rem level: Windows PowerShell turns native stderr into errors when the redirect is inside the same session, so
rem "hu.cmd --version" in install.ps1 would fail on any JVM note. The child writes the installer's exception
rem message to HU_UPDATE_ERROR as the reason. Status texts are char codes to keep this file ASCII:
rem "update in progress", "update done", "update failed: <reason>", "update failed, add --debug for the log".
if defined HU_UPDATE (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Continue'; function Text([int[]] $c) { -join [char[]] $c }; $failed = Text 0x66f4,0x65b0,0x5931,0x8d25; $debugUpdate = $env:HU_UPDATE_DEBUG -eq '1'; $code = 0; $reason = $null; Write-Host (Text 0x66f4,0x65b0,0x4e2d); $env:HU_UPDATE_ERROR = Join-Path $env:TEMP ('hu-update-' + [guid]::NewGuid().ToString('N') + '.txt'); try { if ($env:HU_UPDATE -eq 'local') { $env:HU_UPDATE_INSTALLER = Join-Path (Get-Location).ProviderPath 'install.ps1'; if (-not (Test-Path -LiteralPath 'src\dist\bin\hu.cmd')) { $code = 2; $reason = 'Run hu update --local in the hu-agent source directory.' } elseif ($debugUpdate) { Write-Host 'hu: building the source in the current directory, tests skipped...'; & .\mvnw.cmd -q package -DskipTests; $code = $LASTEXITCODE } else { $null = & .\mvnw.cmd -q package -DskipTests 2>&1; $code = $LASTEXITCODE } } elseif ($env:HU_UPDATE -eq 'pkg') { $env:HU_UPDATE_INSTALLER = Join-Path $env:HU_APP 'install.ps1'; if (-not (Test-Path -LiteralPath $env:HU_UPDATE_INSTALLER)) { $code = 1; $reason = $env:HU_UPDATE_INSTALLER + ' not found. Re-run the installer once to get it.' } }; if ($code -eq 0) { $run = if ($env:HU_UPDATE -eq 'release') { '[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; irm https://raw.githubusercontent.com/lihu-001/hu-agent/main/install.ps1 | iex' } else { '& $env:HU_UPDATE_INSTALLER' }; $child = '$ErrorActionPreference = ''Stop''; try { ' + $run + '; exit $LASTEXITCODE } catch { [IO.File]::WriteAllText($env:HU_UPDATE_ERROR, $_.Exception.Message); throw }'; if ($debugUpdate) { & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $child } else { $null = & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $child 2>&1 }; $code = $LASTEXITCODE; if ($code -ne 0 -and (Test-Path -LiteralPath $env:HU_UPDATE_ERROR)) { $reason = [IO.File]::ReadAllText($env:HU_UPDATE_ERROR) } } } catch { $code = 1; $reason = $_.Exception.Message } finally { Remove-Item -LiteralPath $env:HU_UPDATE_ERROR -Force -ErrorAction SilentlyContinue }; if ($code -eq 0) { Write-Host (Text 0x66f4,0x65b0,0x5b8c,0x6210); exit 0 }; if ($reason) { $line = $failed + (Text 0xff1a) + ($reason.Trim() -split '\r?\n')[0] } elseif ($debugUpdate) { $line = $failed } else { $line = $failed + (Text 0xff0c,0x52a0) + ' --debug ' + (Text 0x67e5,0x770b,0x8be6,0x7ec6,0x65e5,0x5fd7) }; [Console]::Error.WriteLine($line); exit $code"
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
