@echo off
rem hu-agent launcher. Layout: <HU_HOME>\app\bin\hu.cmd, Java runtime is always <HU_HOME>\runtime.
rem HU_JAVA overrides java.exe (development only); HU_JAVA_OPTS appends JVM options.
rem Keep this file ASCII-only: cmd.exe parses batch files in the console code page.
setlocal
for %%I in ("%~dp0..") do set "HU_APP=%%~fI"
for %%I in ("%~dp0..\..") do set "HU_ROOT=%%~fI"

rem hu update: reinstall with install.ps1. No option: the latest release (installer from GitHub).
rem --local [<dir>]: build the source tree in <dir> (default: the current directory); --pkg: a local hu-agent.zip or a
rem directory holding one.
rem Both pass the package through HU_PACKAGE; --pkg uses the installer shipped in app\, so it works offline.
rem By default a successful update shows only the status; a failure prints the build and installer output, then
rem one line with the reason. --debug / -d (before the source option) shows that output live, even on success.
set "HU_UPDATE="
set "HU_UPDATE_DEBUG="
set "HU_UPDATE_SOURCE="
set "HU_UPDATE_ARG="
if /i "%~1"=="update" if /i "%~2"=="--debug" set "HU_UPDATE_DEBUG=1"
if /i "%~1"=="update" if /i "%~2"=="-d" set "HU_UPDATE_DEBUG=1"
if defined HU_UPDATE_DEBUG shift /2
if /i "%~1"=="update" if "%~2"=="" set "HU_UPDATE=release"
if /i "%~1"=="update" if /i "%~2"=="--local" if "%~4"=="" set "HU_UPDATE=local"
if /i "%~1"=="update" if /i "%~2"=="--pkg" if not "%~3"=="" if "%~4"=="" set "HU_UPDATE=pkg"
rem The source directory of --local is resolved against the current directory; an argument starting with - is a
rem misplaced option, not a directory.
if "%HU_UPDATE%"=="local" set "HU_UPDATE_SOURCE=%CD%"
if "%HU_UPDATE%"=="local" if not "%~3"=="" set "HU_UPDATE_SOURCE=%~f3"
if "%HU_UPDATE%"=="local" set "HU_UPDATE_ARG=%~3"
if defined HU_UPDATE_ARG if "%HU_UPDATE_ARG:~0,1%"=="-" set "HU_UPDATE="
if /i "%~1"=="update" if not defined HU_UPDATE (
    echo hu: usage: hu update [--debug ^| -d] [--local [^<source dir^>] ^| --pkg ^<package^>] 1>&2
    exit /b 2
)
if "%HU_UPDATE%"=="pkg" set "HU_PACKAGE=%~3"
if defined HU_UPDATE set "HU_HOME=%HU_ROOT%"
if defined HU_UPDATE setlocal EnableDelayedExpansion
rem Parse the entire execution block before the installer replaces this launcher.
rem Keep exit /b directly in this block so the caller receives the actual exit code.
rem The build and the installer each run as a child process, and quiet mode collects their output at the process
rem level, printing it to stderr only when the update fails: Windows PowerShell turns native stderr into errors when
rem the redirect is inside the same session, so "hu.cmd --version" in install.ps1 would fail on any JVM note. The
rem child writes the installer's exception message to HU_UPDATE_ERROR as the reason. Status texts are char codes to
rem keep this file ASCII: "update in progress", "update done", "update failed", "update failed: <reason>".
rem The build output is collected in both modes (mvnw -q prints little on success). A build stopped by the pom's
rem require-jdk-25 rule, or finding no compiler because JAVA_HOME is a JRE, gets a reason in char codes: building
rem needs JDK 25, the Java bundled with hu is a JRE that cannot compile, point JAVA_HOME to a JDK 25 first.
if defined HU_UPDATE (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Continue'; function Text([int[]] $c) { -join [char[]] $c }; $failed = Text 0x66f4,0x65b0,0x5931,0x8d25; $debugUpdate = $env:HU_UPDATE_DEBUG -eq '1'; $code = 0; $reason = $null; $buildLog = @(); $installLog = @(); Write-Host (Text 0x66f4,0x65b0,0x4e2d); $env:HU_UPDATE_ERROR = Join-Path $env:TEMP ('hu-update-' + [guid]::NewGuid().ToString('N') + '.txt'); try { if ($env:HU_UPDATE -eq 'local') { $source = $env:HU_UPDATE_SOURCE; $env:HU_UPDATE_INSTALLER = Join-Path $source 'install.ps1'; $env:HU_PACKAGE = Join-Path $source 'target\hu-agent.zip'; if (-not (Test-Path -LiteralPath (Join-Path $source 'src\dist\bin\hu.cmd'))) { $code = 2; $reason = 'Not a hu-agent source directory: ' + $source + '. Run hu update --local in the source directory, or pass it: hu update --local <dir>.' } else { Set-Location -LiteralPath $source; if ($debugUpdate) { Write-Host ('hu: building ' + $source + ', tests skipped...') }; $buildLog = @(& .\mvnw.cmd -q package -DskipTests 2>&1); $code = $LASTEXITCODE; if ($code -ne 0 -and @($buildLog -match 'require-jdk-25|No compiler is provided').Count -gt 0) { $reason = (Text 0x6784,0x5efa,0x9700,0x8981) + ' JDK 25' + (Text 0xff1a) + 'hu ' + (Text 0x81ea,0x5e26,0x7684) + ' Java ' + (Text 0x662f) + ' JRE' + (Text 0xff0c,0x4e0d,0x80fd,0x7f16,0x8bd1,0x6e90,0x7801,0x3002,0x8bf7,0x5148,0x628a) + ' JAVA_HOME ' + (Text 0x6307,0x5411) + ' JDK 25 ' + (Text 0x518d,0x8fd0,0x884c,0xff0c,0x4f8b,0x5982) + ' $env:JAVA_HOME = ''C:\path\to\jdk-25''' }; if ($debugUpdate) { foreach ($entry in $buildLog) { Write-Host ([string]$entry) }; $buildLog = @() } } } elseif ($env:HU_UPDATE -eq 'pkg') { $env:HU_UPDATE_INSTALLER = Join-Path $env:HU_APP 'install.ps1'; if (-not (Test-Path -LiteralPath $env:HU_UPDATE_INSTALLER)) { $code = 1; $reason = $env:HU_UPDATE_INSTALLER + ' not found. Re-run the installer once to get it.' } }; if ($code -eq 0) { $run = if ($env:HU_UPDATE -eq 'release') { '[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12; irm https://raw.githubusercontent.com/lihu-001/hu-agent/main/install.ps1 | iex' } else { '& $env:HU_UPDATE_INSTALLER' }; $child = '$ErrorActionPreference = ''Stop''; try { ' + $run + '; exit $LASTEXITCODE } catch { [IO.File]::WriteAllText($env:HU_UPDATE_ERROR, $_.Exception.Message); throw }'; if ($debugUpdate) { & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $child } else { $installLog = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $child 2>&1) }; $code = $LASTEXITCODE; if ($code -ne 0 -and (Test-Path -LiteralPath $env:HU_UPDATE_ERROR)) { $reason = [IO.File]::ReadAllText($env:HU_UPDATE_ERROR) } } } catch { $code = 1; $reason = $_.Exception.Message } finally { Remove-Item -LiteralPath $env:HU_UPDATE_ERROR -Force -ErrorAction SilentlyContinue }; if ($code -eq 0) { Write-Host (Text 0x66f4,0x65b0,0x5b8c,0x6210); exit 0 }; foreach ($entry in @($buildLog + $installLog)) { [Console]::Error.WriteLine([string]$entry) }; if ($reason) { $line = $failed + (Text 0xff1a) + ($reason.Trim() -split '\r?\n')[0] } else { $line = $failed }; [Console]::Error.WriteLine($line); exit $code"
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
