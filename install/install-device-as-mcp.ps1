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
$assetName = "device-as-mcp-windows-x64.zip"
$binaryName = "device-as-mcp.exe"
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) "device-as-mcp-$([Guid]::NewGuid().ToString('N'))"
$temporaryArchive = Join-Path $temporaryDirectory $assetName
$extractionDirectory = Join-Path $temporaryDirectory "extracted"
$temporaryBinary = Join-Path $extractionDirectory $binaryName
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
    Download-File -Url "$releaseBase/$assetName" -Destination $temporaryArchive
    Download-File -Url "$releaseBase/SHA256SUMS" -Destination $checksumPath

    $escapedAssetName = [Regex]::Escape($assetName)
    $checksumLine = Get-Content $checksumPath |
        Where-Object { $_ -match "^[0-9a-fA-F]{64}\s+\*?$escapedAssetName$" } |
        Select-Object -First 1
    if (-not $checksumLine) {
        throw "No checksum found for $assetName."
    }

    $expectedChecksum = ($checksumLine -split "\s+")[0].ToLowerInvariant()
    $actualChecksum = (Get-FileHash -Algorithm SHA256 $temporaryArchive).Hash.ToLowerInvariant()
    if ($actualChecksum -ne $expectedChecksum) {
        throw "Checksum verification failed for $assetName."
    }

    New-Item -ItemType Directory -Path $extractionDirectory | Out-Null
    Expand-Archive -LiteralPath $temporaryArchive -DestinationPath $extractionDirectory
    $files = @(Get-ChildItem -LiteralPath $extractionDirectory -File -Recurse)
    $directories = @(Get-ChildItem -LiteralPath $extractionDirectory -Directory -Recurse)
    if (
        $files.Count -ne 1 -or
        $directories.Count -ne 0 -or
        $files[0].FullName -ne $temporaryBinary
    ) {
        throw "Unexpected archive contents for $assetName."
    }

    $installDirectory = Join-Path $env:LOCALAPPDATA "DeviceAsMcp"
    $installPath = Join-Path $installDirectory "device-as-mcp.exe"
    $scheduledTaskName = "DeviceAsMcp"
    New-Item -ItemType Directory -Force -Path $installDirectory | Out-Null

    Stop-ScheduledTask -TaskName $scheduledTaskName -ErrorAction SilentlyContinue
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

    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $powerShellPath = Join-Path $env:WINDIR "System32\WindowsPowerShell\v1.0\powershell.exe"
    $escapedInstallPath = $installPath.Replace("'", "''")
    $taskCommand = "& '$escapedInstallPath' run; exit `$LASTEXITCODE"
    $taskArguments = "-NoProfile -NonInteractive -WindowStyle Hidden -Command `"$taskCommand`""
    $taskAction = New-ScheduledTaskAction -Execute $powerShellPath -Argument $taskArguments
    $taskTrigger = New-ScheduledTaskTrigger -AtLogOn -User $currentUser
    $taskPrincipal = New-ScheduledTaskPrincipal `
        -UserId $currentUser `
        -LogonType Interactive `
        -RunLevel Limited
    $taskSettings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -MultipleInstances IgnoreNew `
        -RestartCount 999 `
        -RestartInterval (New-TimeSpan -Minutes 1) `
        -StartWhenAvailable
    $scheduledTask = New-ScheduledTask `
        -Action $taskAction `
        -Trigger $taskTrigger `
        -Principal $taskPrincipal `
        -Settings $taskSettings `
        -Description "Run DeviceAsMcp after this user logs in."
    Register-ScheduledTask `
        -TaskName $scheduledTaskName `
        -InputObject $scheduledTask `
        -Force | Out-Null

    $startupDirectory = [Environment]::GetFolderPath([Environment+SpecialFolder]::Startup)
    Remove-Item `
        -LiteralPath (Join-Path $startupDirectory "DeviceAsMcp.vbs") `
        -Force `
        -ErrorAction SilentlyContinue
    Start-ScheduledTask -TaskName $scheduledTaskName

    Write-Host "DeviceAsMcp is supervised by a task that starts when this user logs in."
    Write-Host "Installed $installPath"
} finally {
    Remove-Item -Recurse -Force $temporaryDirectory -ErrorAction SilentlyContinue
}
