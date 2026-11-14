$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$psql = 'C:\Program Files\PostgreSQL\17\bin\psql.exe'
$envFile = Join-Path $root '.env.production'
if (Test-Path $envFile) { throw '.env.production already exists. Delete it yourself first if you want to redo this.' }

$ref = 'wqjkimzrvhlqjjvmbovn'
$dbHost = Read-Host 'Session pooler host (e.g. aws-0-xx-xxxx-x.pooler.supabase.com)'
$adminUser = Read-Host "Pooler user (default postgres.$ref)"
if (-not $adminUser) { $adminUser = "postgres.$ref" }
$secure = Read-Host 'Supabase database password' -AsSecureString
$adminPw = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))

function New-Secret($n) { -join ((48..57 + 65..90 + 97..122) | Get-Random -Count $n | ForEach-Object { [char]$_ }) }
$env:PGPASSWORD = $adminPw
$env:PGSSLMODE = 'require'
$conn = @('-h', $dbHost, '-p', '5432', '-U', $adminUser, '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-X', '-q')

$existing = & $psql @conn -tAc "select string_agg(rolname, ',') from pg_roles where rolname in ('misva_owner','misva_app') union all select string_agg(nspname, ',') from pg_namespace where nspname = 'miseva'"
if ($existing -and ($existing -join '').Trim()) { throw "Found existing roles or schema ($existing). Nothing changed." }

Write-Host 'This creates roles misva_owner and misva_app and the schema "miseva" in your Supabase database. Nothing existing is touched.'
if ((Read-Host 'Type yes to continue') -ne 'yes') { throw 'Cancelled.' }

$ownerPw = New-Secret 32
$appPw = New-Secret 32
$jwt = New-Secret 64
$sql = @"
CREATE ROLE misva_owner LOGIN PASSWORD '$ownerPw' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE misva_app LOGIN PASSWORD '$appPw' NOSUPERUSER NOBYPASSRLS;
GRANT misva_owner TO CURRENT_USER;
"@
$sql | & $psql @conn
& $psql @conn -f (Join-Path $root 'database\schema-setup.sql')

$verify = & $psql @conn -tAc "select has_schema_privilege('anon','miseva','USAGE') or has_schema_privilege('authenticated','miseva','USAGE')"
if ($verify.Trim() -ne 'f') { throw 'anon/authenticated can access the miseva schema. Stopping.' }

@"
DB_HOST=$dbHost
DB_PORT=5432
DB_NAME=postgres
DB_SCHEMA=miseva
DB_URL_PARAMS=&sslmode=require&prepareThreshold=0
DB_OWNER_USER=misva_owner.$ref
DB_OWNER_PASSWORD=$ownerPw
DB_APP_USER=misva_app.$ref
DB_APP_PASSWORD=$appPw
JWT_SECRET=$jwt
"@ | Set-Content -Path $envFile -Encoding ascii
Write-Host 'Done. Roles and schema created; anon/authenticated have no access. Secrets saved to .env.production (git-ignored).'
Remove-Item Env:PGPASSWORD
