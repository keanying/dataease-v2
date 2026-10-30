<#
.SYNOPSIS
  在 Windows 本机启动 DataEase（开发 / 试用）。

.DESCRIPTION
  依次检查 JDK 21、CoreApplication.jar（是否包含前端页面）、MySQL 是否可连接，
  准备数据目录（JDBC 驱动、静态资源、地图文件），然后启动服务。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File installer\windows\start.ps1 -DbUser dataease -DbPassword 你的密码
#>
param(
    [string]$DbHost = "localhost",
    [int]$DbPort = 3306,
    [string]$DbName = "dataease",
    [string]$DbUser = "root",
    [string]$DbPassword = "123456",
    [int]$Port = 8100,
    [string]$Xmx = "2g"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $Root

function Fail($msg) {
    Write-Host "[失败] $msg" -ForegroundColor Red
    exit 1
}

function Ok($msg) {
    Write-Host "[通过] $msg" -ForegroundColor Green
}

# 1. JDK 21
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) { Fail "找不到 java，请安装 JDK 21 并加入 PATH" }
$javaVersion = (& java -version 2>&1 | Select-Object -First 1).ToString()
if ($javaVersion -notmatch '"(\d+)') { Fail "无法识别 Java 版本：$javaVersion" }
if ([int]$Matches[1] -lt 21) { Fail "需要 JDK 21，当前是：$javaVersion" }
Ok "Java：$javaVersion"

# 2. jar 及其中的前端页面
$jar = Join-Path $Root "core\core-backend\target\CoreApplication.jar"
if (-not (Test-Path $jar)) {
    Fail "找不到 $jar，请先构建：前端 npm run build:distributed，再 cd core; mvn clean package -Pstandalone -DskipTests"
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $hasFront = $zip.Entries | Where-Object { $_.FullName -like "BOOT-INF/classes/static/index.html" } | Select-Object -First 1
} finally {
    $zip.Dispose()
}
if (-not $hasFront) {
    Write-Host "[警告] jar 中没有前端页面，浏览器将无法打开界面。请先构建前端再重新打包后端。" -ForegroundColor Yellow
} else {
    Ok "jar 中包含前端页面"
}

# 3. MySQL 端口
$tcp = New-Object System.Net.Sockets.TcpClient
try {
    $async = $tcp.BeginConnect($DbHost, $DbPort, $null, $null)
    if (-not $async.AsyncWaitHandle.WaitOne(3000) -or -not $tcp.Connected) {
        Fail "连不上数据库 ${DbHost}:${DbPort}，请确认 MySQL 已启动"
    }
} catch {
    Fail "连不上数据库 ${DbHost}:${DbPort}：$($_.Exception.Message)"
} finally {
    $tcp.Close()
}
Ok "数据库端口 ${DbHost}:${DbPort} 可连接（库 $DbName 需已创建，用户需有建表权限）"

# 4. 数据目录：程序使用 /opt/dataease3.0，在 Windows 上对应当前盘符根目录
$drive = (Get-Location).Drive.Name + ":"
$base = "$drive\opt\dataease3.0"
$dirs = @("drivers", "data\static-resource", "data\map-origin", "logs")
foreach ($d in $dirs) { New-Item -ItemType Directory -Force (Join-Path $base $d) | Out-Null }
if (-not (Get-ChildItem (Join-Path $base "drivers") -Filter *.jar -ErrorAction SilentlyContinue)) {
    Copy-Item (Join-Path $Root "drivers\*") (Join-Path $base "drivers") -Force
}
if (Test-Path (Join-Path $Root "staticResource")) {
    Copy-Item (Join-Path $Root "staticResource\*") (Join-Path $base "data\static-resource") -Recurse -Force
}
if (Test-Path (Join-Path $Root "mapFiles")) {
    Copy-Item (Join-Path $Root "mapFiles\*") (Join-Path $base "data\map-origin") -Recurse -Force
}
Ok "数据目录：$base"

# 5. 启动
$params = "autoReconnect=false&useUnicode=true&characterEncoding=UTF-8&characterSetResults=UTF-8&zeroDateTimeBehavior=convertToNull&useSSL=false&allowPublicKeyRetrieval=true"
$url = "jdbc:mariadb://${DbHost}:${DbPort}/${DbName}?$params"
Write-Host ""
Write-Host "正在启动，首次启动需要建表，约 30~90 秒。看到 Started CoreApplication 后访问：" -ForegroundColor Cyan
Write-Host "  http://localhost:$Port    账号 admin / 密码 DataEase@123456" -ForegroundColor Cyan
Write-Host "  日志目录：$base\logs\dataease   按 Ctrl+C 停止" -ForegroundColor Cyan
Write-Host ""

& java "-Xmx$Xmx" "-Dfile.encoding=UTF-8" -jar $jar `
    "--server.port=$Port" `
    "--spring.datasource.url=$url" `
    "--spring.datasource.username=$DbUser" `
    "--spring.datasource.password=$DbPassword"
