[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ServerUrl,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Token,

    [ValidateNotNullOrEmpty()]
    [string]$Name = $env:COMPUTERNAME,

    [ValidateSet("windows-x64")]
    [string]$Platform = "windows-x64"
)

$ErrorActionPreference = "Stop"

if (-not [Environment]::Is64BitOperatingSystem) {
    throw "DeviceAsMcp currently supports only Windows x64."
}

$repository = if ($env:DEVICE_AS_MCP_GITHUB_REPOSITORY) {
    $env:DEVICE_AS_MCP_GITHUB_REPOSITORY
} else {
    "Stream29/DeviceAsMcp"
}
$releaseBase = if ($env:DEVICE_AS_MCP_RELEASE_BASE_URL) {
    $env:DEVICE_AS_MCP_RELEASE_BASE_URL.TrimEnd("/")
} else {
    "https://github.com/$repository/releases/latest/download"
}
$assetName = "device-as-mcp-windows-x64.exe"
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) "device-as-mcp-$([Guid]::NewGuid().ToString('N'))"
$temporaryBinary = Join-Path $temporaryDirectory $assetName
$checksumPath = Join-Path $temporaryDirectory "SHA256SUMS"

function Download-File {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url,

        [Parameter(Mandatory = $true)]
        [string]$Destination
    )

    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination
}

New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
try {
    Write-Host "Downloading $assetName from GitHub..."
    Download-File -Url "$releaseBase/$assetName" -Destination $temporaryBinary
    Download-File -Url "$releaseBase/SHA256SUMS" -Destination $checksumPath

    $escapedAssetName = [Regex]::Escape($assetName)
    $checksumLine = Get-Content $checksumPath |
        Where-Object { $_ -match "^[0-9a-fA-F]{64}\s+\*?$escapedAssetName$" } |
        Select-Object -First 1
    if (-not $checksumLine) {
        throw "No checksum found for $assetName."
    }

    $expectedChecksum = ($checksumLine -split "\s+")[0].ToLowerInvariant()
    $actualChecksum = (Get-FileHash -Algorithm SHA256 $temporaryBinary).Hash.ToLowerInvariant()
    if ($actualChecksum -ne $expectedChecksum) {
        throw "Checksum verification failed for $assetName."
    }

    $installDirectory = Join-Path $env:LOCALAPPDATA "DeviceAsMcp"
    $installPath = Join-Path $installDirectory "device-as-mcp.exe"
    New-Item -ItemType Directory -Force -Path $installDirectory | Out-Null

    Get-CimInstance Win32_Process -Filter "Name = 'device-as-mcp.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.ExecutablePath -eq $installPath } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

    $newInstallPath = "$installPath.new"
    Copy-Item -Force $temporaryBinary $newInstallPath
    Move-Item -Force $newInstallPath $installPath

    Write-Host "Enrolling this device..."
    & $installPath enroll --server $ServerUrl --token $Token --name $Name --no-run
    if ($LASTEXITCODE -ne 0) {
        throw "Device enrollment failed with exit code $LASTEXITCODE."
    }

    $startupDirectory = [Environment]::GetFolderPath([Environment+SpecialFolder]::Startup)
    $launcherPath = Join-Path $startupDirectory "DeviceAsMcp.vbs"
    $escapedInstallPath = $installPath.Replace('"', '""')
    $launcher = @"
Set shell = CreateObject("WScript.Shell")
shell.Run """$escapedInstallPath"" run", 0, False
"@
    [IO.File]::WriteAllText($launcherPath, $launcher, [Text.Encoding]::ASCII)

    Start-Process `
        -FilePath (Join-Path $env:WINDIR "System32\wscript.exe") `
        -ArgumentList @("//B", "`"$launcherPath`"")

    Write-Host "DeviceAsMcp is installed and starts automatically when this user logs in."
    Write-Host "Installed $installPath"
} finally {
    Remove-Item -Recurse -Force $temporaryDirectory -ErrorAction SilentlyContinue
}
