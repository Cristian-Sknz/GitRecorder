param(
    [string]$InstallDir = (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Programs\GitRecorder'),
    [string]$Store
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$binary = Join-Path $projectRoot 'build\native\nativeCompile\gitrecorder.exe'
$installDir = [IO.Path]::GetFullPath($InstallDir)

if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) {
    throw "Executável não encontrado: $binary. Execute o build nativo primeiro."
}

New-Item -ItemType Directory -Path $installDir -Force | Out-Null
Copy-Item -LiteralPath $binary -Destination (Join-Path $installDir 'gitrecorder.exe') -Force

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$entries = @($userPath -split ';' | Where-Object { $_.Trim() })
$present = $false
foreach ($entry in $entries) {
    if ([string]::Equals($entry.Trim().Trim('"').TrimEnd('\', '/'), $installDir,
            [StringComparison]::OrdinalIgnoreCase)) { $present = $true; break }
}
if (-not $present) {
    $newPath = if ([string]::IsNullOrWhiteSpace($userPath)) { $installDir }
        else { $userPath.TrimEnd(';') + ';' + $installDir }
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
}
$env:Path = $env:Path.TrimEnd(';') + ';' + $installDir

if ($Store) {
    $storePath = [IO.Path]::GetFullPath($Store)
    [Environment]::SetEnvironmentVariable('GITRECORDER_STORE', $storePath, 'User')
    $env:GITRECORDER_STORE = $storePath
}

Write-Output "GitRecorder instalado em: $installDir"
Write-Output 'Abra um novo PowerShell para usar gitrecorder em qualquer pasta.'
