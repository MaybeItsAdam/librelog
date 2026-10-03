<#
.SYNOPSIS
  Builds the Rust core for Windows and the WinUI app.

.DESCRIPTION
  1. Builds core/ with the C ABI (the capi feature) for each platform, giving
     core/target/<triple>/release/librelog_core.dll, which the app bundles.
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
  dotnet test (Join-Path $windows 'tests/Librelog.Shared.Tests') --nologo
  if ($LASTEXITCODE -ne 0) { throw 'dotnet test failed' }
}

$app = Join-Path $windows 'src/Librelog.App/Librelog.App.csproj'
foreach ($platform in $Platforms) {
  $rid = if ($platform -eq 'ARM64') { 'win-arm64' } else { 'win-x64' }
  # Prefer Visual Studio's MSBuild: it runs the XAML compiler in-process and
  # reports its errors, where `dotnet build` can fail with only "exited with
  # code 1". Falls back to the .NET SDK when MSBuild isn't on PATH.
  $msbuild = Get-Command msbuild -ErrorAction SilentlyContinue
  $config = if ($Publish) { 'Release' } else { 'Debug' }
  # NativeAOT only works self-contained: the app carries its own runtime.
  $extra = if ($Publish) { @('/p:GenerateAppxPackageOnBuild=true', '/p:SelfContained=true', '/p:PublishAot=true') } else { @() }
  Write-Host "› $(if ($Publish) { 'publish' } else { 'build' }) app ($platform, $config, $(if ($msbuild) { 'MSBuild' } else { 'dotnet' }))"
  if ($msbuild) {
    & $msbuild.Source $app /restore /m /nologo /v:minimal "/p:Configuration=$config" "/p:Platform=$platform" "/p:RuntimeIdentifier=$rid" @extra
  } elseif ($Publish) {
    dotnet publish $app -c Release -p:Platform=$platform -r $rid -p:GenerateAppxPackageOnBuild=true --nologo
  } else {
    dotnet build $app -c Debug -p:Platform=$platform -r $rid --nologo
  }
  if ($LASTEXITCODE -ne 0) {
    # The XAML compiler reports its errors in output.json, which `dotnet
    # build` never prints: "XamlCompiler.exe exited with code 1" is all you
    # get. Show them.
    $obj = Join-Path (Split-Path $app) 'obj'
    Get-ChildItem $obj -Recurse -Filter output.json -ErrorAction SilentlyContinue | ForEach-Object {
      Write-Host "--- $($_.FullName)"
      Get-Content $_.FullName | Write-Host
    }
    throw "app build failed for $platform"
  }
}

Write-Host '✓ done'
