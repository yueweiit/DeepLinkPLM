param(
    [Parameter(Mandatory = $true)][string]$SchemaDumpPath,
    [string]$MavenCommand = 'mvn',
    [string]$PostgresImage = 'postgres:17-alpine',
    [switch]$KeepTestContainer
)

# Schema-only input. This runner never connects to, migrates or restarts a business DB.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$dumpPath = (Resolve-Path -LiteralPath $SchemaDumpPath).Path
if ((Get-Content -LiteralPath $dumpPath -Raw) -match '(?im)^COPY\s+[^\r\n]+FROM stdin;\s*$') {
    throw 'Use a pg_dump --schema-only SQL file, not a data backup.'
}
$testContainerName = 'plm-crm-openapi-test-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$testContainerId = $null
$previousTestUrl = [Environment]::GetEnvironmentVariable('PLM_CRM_OPENAPI_TEST_DB_URL', 'Process')

function Invoke-DockerChecked {
    param([string[]]$DockerArguments)
    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($DockerArguments[0])" }
}

try {
    $created = & docker create --name $testContainerName --label plm.crm.openapi.test=true `
        -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=crm_openapi_it `
        -p 127.0.0.1:15519:5432 --tmpfs /var/lib/postgresql/data $PostgresImage
    if ($LASTEXITCODE -ne 0) { throw 'Could not create disposable PostgreSQL container.' }
    $testContainerId = ($created | Select-Object -Last 1).Trim()
    if ($testContainerId -notmatch '^[a-f0-9]{64}$') { throw 'Unexpected container identifier.' }
    Invoke-DockerChecked @('start', $testContainerId)
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker exec $testContainerId pg_isready -U postgres -d crm_openapi_it *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Disposable PostgreSQL did not become ready.' }
    Invoke-DockerChecked @('cp', $dumpPath, "${testContainerId}:/tmp/source-schema.sql")
    $migrationRoot = Join-Path $repoRoot 'plm-server/src/main/resources/db/migration'
    Invoke-DockerChecked @('cp', (Join-Path $migrationRoot 'V20261109_1900__crm_openapi_sync_foundation.sql'), "${testContainerId}:/tmp/foundation.sql")
    Invoke-DockerChecked @('cp', (Join-Path $migrationRoot 'V20261109_1910__crm_openapi_source_capture.sql'), "${testContainerId}:/tmp/capture.sql")
    Invoke-DockerChecked @('cp', (Join-Path $migrationRoot 'V20261111_1000__crm_openapi_separate_products_skus.sql'), "${testContainerId}:/tmp/products-skus.sql")
    Invoke-DockerChecked @('exec', $testContainerId, 'psql', '-U', 'postgres', '-d', 'crm_openapi_it', '-v', 'ON_ERROR_STOP=1', '-q', '-f', '/tmp/source-schema.sql', '-c', 'set search_path=plm,public', '-f', '/tmp/foundation.sql', '-f', '/tmp/capture.sql', '-f', '/tmp/products-skus.sql')
    $env:PLM_CRM_OPENAPI_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:15519/crm_openapi_it?currentSchema=plm'
    & $MavenCommand -f (Join-Path $repoRoot 'plm-server/pom.xml') '-Dtest=CrmOpenApiUnitTest,CrmOpenApiSecurityTest,CrmOpenApiPostgresTest,MoldScheduleServiceTest,MoldSchedulePolicyTest,MoldExecutionPolicyTest,SecurityConfigTest' test
    if ($LASTEXITCODE -ne 0) { throw 'CRM tests failed; inspect plm-server/target/surefire-reports.' }
} finally {
    [Environment]::SetEnvironmentVariable('PLM_CRM_OPENAPI_TEST_DB_URL', $previousTestUrl, 'Process')
    if ($testContainerId -and $testContainerId -match '^[a-f0-9]{64}$') {
        if ($KeepTestContainer) {
            Write-Host "Disposable test container retained: $testContainerName ($testContainerId)"
        } else {
            # Exact ID created above; never enumerate/delete other containers or volumes.
            & docker rm -f $testContainerId
            Write-Host 'Removed the disposable test container and its temporary test data.'
        }
    }
}
