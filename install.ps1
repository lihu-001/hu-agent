# hu-agent installer for Windows (PowerShell 5.1+):
#   irm https://raw.githubusercontent.com/lihu-001/hu-agent/main/install.ps1 | iex
#
# Layout (re-run to upgrade; config and trust records are kept):
#   %USERPROFILE%\.hu-agent\app\       launcher and hu-agent.jar, replaced on every install; app\bin is added to the user PATH
#   %USERPROFILE%\.hu-agent\runtime\   private Java runtime (pinned Temurin JRE, downloaded again only when the pin changes)
#
# Optional environment variables (same meaning as in install.sh):
#   HU_VERSION, HU_HOME, HU_DOWNLOAD_BASE, HU_JRE_BASE, HU_NO_MODIFY_PATH
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less scripts in the ANSI code page,
# and a BOM breaks "irm | iex".

& {
    $ErrorActionPreference = 'Stop'
    # Windows PowerShell 5.1 downloads much slower while drawing a progress bar.
    $ProgressPreference = 'SilentlyContinue'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

    $Repo = 'lihu-001/hu-agent'
    # Pinned Java runtime. When bumping it, update the file name and sha256 (package.checksum from the Adoptium API).
    # Temurin 17 has no Windows ARM64 build; ARM64 devices run the x64 runtime under emulation.
    $JreRelease = 'jdk-17.0.20.1+1'
    $JreFile = 'OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip'
    $JreSha256 = 'bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352'

    function Say([string] $Text) {
        Write-Host "hu-agent> $Text"
    }

    function Get-HuFile([string] $Url, [string] $Path) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Path
        } catch {
            throw "Download failed: $Url ($($_.Exception.Message))"
        }
    }

    function Assert-HuSha256([string] $Path, [string] $Expected) {
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
        if ($actual -ne $Expected.Trim().ToLowerInvariant()) {
            throw "Checksum mismatch for $(Split-Path -Leaf $Path) (expected $Expected, got $actual)"
        }
    }

    function Expand-HuZip([string] $Path, [string] $Destination) {
        # Much faster than Expand-Archive; the JRE has over a thousand files.
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory($Path, $Destination)
    }

    function Install-HuDirectory([string] $Source, [string] $Target) {
        $old = "$Target.old"
        if (Test-Path -LiteralPath $old) {
            Remove-Item -LiteralPath $old -Recurse -Force
        }
        if (Test-Path -LiteralPath $Target) {
            try {
                Rename-Item -LiteralPath $Target -NewName (Split-Path -Leaf $old)
            } catch {
                throw "Cannot replace $Target. Exit every running hu and try again."
            }
        }
        Move-Item -LiteralPath $Source -Destination $Target
        if (Test-Path -LiteralPath $old) {
            Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    function Test-HuPathEntry([string] $PathValue, [string] $Directory) {
        $wanted = $Directory.TrimEnd('\')
        foreach ($entry in ($PathValue -split ';')) {
            if ($entry -and ([Environment]::ExpandEnvironmentVariables($entry).TrimEnd('\') -ieq $wanted)) {
                return $true
            }
        }
        return $false
    }

    if (-not [Environment]::Is64BitOperatingSystem) {
        throw 'hu-agent requires 64-bit Windows'
    }

    $version = if ($env:HU_VERSION) { $env:HU_VERSION } else { 'latest' }
    $appBase = if ($env:HU_DOWNLOAD_BASE) { $env:HU_DOWNLOAD_BASE.TrimEnd('/') }
               elseif ($version -eq 'latest') { "https://github.com/$Repo/releases/latest/download" }
               else { "https://github.com/$Repo/releases/download/$version" }
    $jreBase = if ($env:HU_JRE_BASE) { $env:HU_JRE_BASE.TrimEnd('/') }
               else { "https://github.com/adoptium/temurin17-binaries/releases/download/$($JreRelease.Replace('+', '%2B'))" }
    $huHome = if ($env:HU_HOME) { $env:HU_HOME } else { Join-Path $env:USERPROFILE '.hu-agent' }
    New-Item -ItemType Directory -Force -Path $huHome | Out-Null
    $huHome = (Resolve-Path -LiteralPath $huHome).ProviderPath

    # Stage inside the install directory so the final step is a move on the same volume.
    $work = Join-Path $huHome (".install-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        # ---- Download and verify everything before touching the installed files ----
        Say "Downloading hu-agent ($version)"
        $appZip = Join-Path $work 'hu-agent.zip'
        Get-HuFile "$appBase/hu-agent.zip" $appZip
        Get-HuFile "$appBase/hu-agent.zip.sha256" "$appZip.sha256"
        Assert-HuSha256 $appZip ((Get-Content -LiteralPath "$appZip.sha256" -Raw).Trim() -split '\s+')[0]
        Expand-HuZip $appZip (Join-Path $work 'app')
        $appSource = Join-Path $work 'app\hu-agent'
        if (-not (Test-Path -LiteralPath (Join-Path $appSource 'lib\hu-agent.jar'))) {
            throw 'The hu-agent package is incomplete'
        }

        $runtime = Join-Path $huHome 'runtime'
        $marker = Join-Path $runtime '.hu-runtime'
        $jreSource = $null
        if ((Test-Path -LiteralPath (Join-Path $runtime 'bin\java.exe')) -and (Test-Path -LiteralPath $marker) `
                -and ((Get-Content -LiteralPath $marker -Raw).Trim() -eq $JreRelease)) {
            Say "Java runtime $JreRelease is already installed"
        } else {
            Say "Downloading Java runtime $JreRelease (about 45 MB, please wait)"
            $jreZip = Join-Path $work 'jre.zip'
            Get-HuFile "$jreBase/$JreFile" $jreZip
            Assert-HuSha256 $jreZip $JreSha256
            Expand-HuZip $jreZip (Join-Path $work 'jre')
            $jreSource = (Get-ChildItem -LiteralPath (Join-Path $work 'jre') -Directory | Select-Object -First 1).FullName
            if (-not $jreSource -or -not (Test-Path -LiteralPath (Join-Path $jreSource 'bin\java.exe'))) {
                throw 'The Java runtime package is incomplete'
            }
            Set-Content -LiteralPath (Join-Path $jreSource '.hu-runtime') -Value $JreRelease -Encoding ASCII
        }

        # ---- Install ----
        if ($jreSource) {
            Install-HuDirectory $jreSource $runtime
        }
        Install-HuDirectory $appSource (Join-Path $huHome 'app')
    } finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }

    $bin = Join-Path $huHome 'app\bin'
    $installed = & (Join-Path $bin 'hu.cmd') --version
    if ($LASTEXITCODE -ne 0) {
        throw 'hu --version failed after installation'
    }

    # ---- PATH ----
    if ($env:HU_NO_MODIFY_PATH -eq '1') {
        Say "Add $bin to your PATH to use hu"
    } else {
        # Read and write the raw registry value: [Environment]::SetEnvironmentVariable would
        # expand entries such as %USERPROFILE% into fixed paths.
        $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
        try {
            $userPath = [string] $key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
            if (-not (Test-HuPathEntry $userPath $bin)) {
                $newPath = if ($userPath.Trim()) { $userPath.TrimEnd(';') + ';' + $bin } else { $bin }
                $key.SetValue('Path', $newPath, [Microsoft.Win32.RegistryValueKind]::ExpandString)
                Say "Added $bin to the user PATH"
            }
        } finally {
            $key.Close()
        }
        # Setting and clearing a dummy variable broadcasts WM_SETTINGCHANGE, so new terminals see the PATH.
        [Environment]::SetEnvironmentVariable('HU_AGENT_PATH_REFRESH', '1', 'User')
        [Environment]::SetEnvironmentVariable('HU_AGENT_PATH_REFRESH', $null, 'User')
    }
    if (-not (Test-HuPathEntry $env:Path $bin)) {
        $env:Path = "$bin;$env:Path"
    }

    Say "Installed $installed ($huHome)"
    Say 'Run hu in any project directory. The first run asks for the model endpoint and API key.'
}
