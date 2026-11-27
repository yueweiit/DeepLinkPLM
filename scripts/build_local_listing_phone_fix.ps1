$ErrorActionPreference = 'Stop'
$source = Split-Path $PSScriptRoot -Parent
$target = Join-Path $source 'plm-server/target-listing-fix-20261112'
$java = 'D:/work/Yuewei/.jdks/jdk-17/bin/jar.exe'
$container = 'plm-local-20261106-server-1'
$image = 'yuewei-plm-server:local-listing-phone-fix-20261112'
$baselineId = (docker inspect $container --format '{{.Image}}').Trim()
if ($LASTEXITCODE) { throw 'Cannot inspect local backend' }
$temporary = 'plm-listing-phone-fix-baseline-20261112'
docker create --name $temporary $baselineId | Out-Null
if ($LASTEXITCODE) { throw 'Cannot create baseline container' }
try {
    docker cp "${temporary}:/app/app.jar" "$target/baseline.jar"
    if ($LASTEXITCODE) { throw 'Cannot extract baseline' }
} finally { docker rm $temporary | Out-Null }
Copy-Item "$target/baseline.jar" "$target/app.jar"
$package = 'com/yuewei/plm/module/partner'
$stage = Join-Path $target "stage/BOOT-INF/classes/$package"
New-Item -ItemType Directory -Force $stage | Out-Null
foreach ($name in @('PartnerCatalogStyles', 'PartnerListingService')) {
    Copy-Item "$target/classes/$package/$name.class" $stage
}
& $java uf "$target/app.jar" -C "$target/stage" BOOT-INF
if ($LASTEXITCODE) { throw 'JAR update failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Entries($file) {
    $zip = [IO.Compression.ZipFile]::OpenRead($file)
    try {
        $result = @{}
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            $stream = $entry.Open(); $hash = [Security.Cryptography.SHA256]::Create()
            try { $result[$entry.FullName] = [BitConverter]::ToString($hash.ComputeHash($stream)) }
            finally { $stream.Dispose(); $hash.Dispose() }
        }
        return $result
    } finally { $zip.Dispose() }
}
$before = Entries "$target/baseline.jar"; $after = Entries "$target/app.jar"
$allowed = @("BOOT-INF/classes/$package/PartnerCatalogStyles.class", "BOOT-INF/classes/$package/PartnerListingService.class")
foreach ($name in $before.Keys) {
    if (!$after.ContainsKey($name)) { throw "Removed entry: $name" }
    if ($before[$name] -ne $after[$name] -and $name -notin $allowed) { throw "Unexpected change: $name" }
}
foreach ($name in $after.Keys) { if (!$before.ContainsKey($name)) { throw "Unexpected entry: $name" } }
if ((docker inspect $container --format '{{.Image}}').Trim() -ne $baselineId) { throw 'Backend changed during build; rebuild from newest runtime' }
Write-Output "Scoped overlay verified: $($allowed -join ', ')"
Write-Output "BASELINE_IMAGE=$baselineId"
Write-Output "JAR=$target/app.jar"
Write-Output "NEXT_IMAGE=$image"
