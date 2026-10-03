<#
.SYNOPSIS
  Builds the Rust core for Windows and the WinUI app.

.DESCRIPTION
  1. Builds core/ with the C ABI (the capi feature) for each platform, giving
     core/target/<triple>/release/dailylog_core.dll, which the app bundles.
     (The Windows app calls the core through core/src/capi.rs rather than
     generated UniFFI bindings: uniffi-bindgen-cs trails the core's uniffi-rs.)
  2. Builds core/ for the host as well, so the .NET tests can load it.
  3. Runs the .NET tests and builds the app.

.EXAMPLE
  scripts/build-windows.ps1                 # x64, tests + app build
  scripts/build-windows.ps1 -Platforms x64,ARM64 -Publish
#>
param(
  [string[]] $Platforms = @('x64'),
  [switch] $Publish,
  [switch] $SkipTests
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$core = Join-Path $root 'core'
$windows = Join-Path $root 'windows'

$triples = @{ 'x64' = 'x86_64-pc-windows-msvc'; 'ARM64' = 'aarch64-pc-windows-msvc' }

foreach ($platform in $Platforms) {
  $triple = $triples[$platform]
  if (-not $triple) { throw "Unknown platform $platform (want x64 or ARM64)" }
  Write-Host "› core for $triple"
  rustup target add $triple | Out-Null
  cargo build --release --lib --features capi --target $triple --manifest-path (Join-Path $core 'Cargo.toml')
  if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $triple" }
}

if (-not $SkipTests) {
  Write-Host '› core for the test host'
  # Own target dir: the Apple and Android scripts also build the host
  # library, without the C ABI, and would otherwise overwrite this one.
  cargo build --release --lib --features capi --target-dir (Join-Path $core 'target/capi') --manifest-path (Join-Path $core 'Cargo.toml')
  if ($LASTEXITCODE -ne 0) { throw 'cargo build failed for the host' }

  Write-Host '› .NET tests'
  dotnet test (Join-Path $windows 'tests/Dailylog.Shared.Tests') --nologo
  if ($LASTEXITCODE -ne 0) { throw 'dotnet test failed' }
}

$app = Join-Path $windows 'src/Dailylog.App/Dailylog.App.csproj'
foreach ($platform in $Platforms) {
  $rid = if ($platform -eq 'ARM64') { 'win-arm64' } else { 'win-x64' }
  if ($Publish) {
    Write-Host "› publish app ($platform, NativeAOT, MSIX)"
    dotnet publish $app -c Release -p:Platform=$platform -r $rid -p:GenerateAppxPackageOnBuild=true --nologo
  } else {
    Write-Host "› build app ($platform)"
    dotnet build $app -c Debug -p:Platform=$platform -r $rid --nologo
  }
  if ($LASTEXITCODE -ne 0) { throw "app build failed for $platform" }
}

Write-Host '✓ done'
