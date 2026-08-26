param(
    [Parameter(Mandatory = $true)]
    [string]$JksPath,
    [Parameter(Mandatory = $true)]
    [string]$StorePass,
    [Parameter(Mandatory = $true)]
    [string]$KeyAlias,
    [Parameter(Mandatory = $true)]
    [string]$KeyPass,
    [string]$ProjectRoot = ".."
)

# 用 keytool 生成 jks -> p12，再用 openssl 转 pem，最后拆成 private.pem + certificate.pem
# 同步到 quickapp/sign/{debug,release} 并更新 companion/signing/keystore.properties

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $ProjectRoot))
$outDir = Join-Path $env:TEMP ("aiquota_sig_" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $outDir | Out-Null

try {
    $p12 = Join-Path $outDir "keystore.p12"
    $pem = Join-Path $outDir "keystore.pem"

    Write-Host "==> jks -> p12"
    keytool -importkeystore -srckeystore $JksPath -destkeystore $p12 `
        -srcstoretype jks -deststoretype pkcs12 `
        -srcstorepass $StorePass -deststorepass $StorePass -srcalias $KeyAlias -destalias $KeyAlias | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }

    Write-Host "==> p12 -> pem"
    openssl pkcs12 -nodes -in $p12 -out $pem -password pass:$StorePass | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "openssl failed" }

    $content = Get-Content -Raw -Encoding UTF8 $pem

    $privMatch = [regex]::Match($content, '(?s)-----BEGIN PRIVATE KEY-----.*?-----END PRIVATE KEY-----')
    $certMatch = [regex]::Match($content, '(?s)-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----')
    if (-not $privMatch.Success -or -not $certMatch.Success) {
        throw "无法从 pem 中拆分私钥/证书"
    }

    foreach ($flavor in @("debug", "release")) {
        $dir = Join-Path $root "quickapp\sign\$flavor"
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        Set-Content -Path (Join-Path $dir "private.pem") -Value ($privMatch.Value + "`n") -Encoding ASCII
        Set-Content -Path (Join-Path $dir "certificate.pem") -Value ($certMatch.Value + "`n") -Encoding ASCII
        Write-Host "==> 已同步 quickapp/sign/$flavor"
    }

    $props = @"
store.file=signing/keystore.jks
store.password=$StorePass
key.alias=$KeyAlias
key.password=$KeyPass
"@
    Set-Content -Path (Join-Path $root "companion\signing\keystore.properties") -Value $props -Encoding ASCII
    Write-Host "==> 已更新 companion/signing/keystore.properties"

    $jksDest = Join-Path $root "companion\signing\keystore.jks"
    Copy-Item $JksPath $jksDest -Force
    Write-Host "==> 已复制 keystore 到 $jksDest"

    Write-Host ""
    Write-Host "完成！请重新构建 Android APK 和手环 rpk。"
} finally {
    Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue
}