$ErrorActionPreference = 'Stop'

# ── 路径全部自动探测，不写死任何本机目录 ──────────────────────────────
$ROOT = $PSScriptRoot

function Resolve-Sdk {
    $cands = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'))
    foreach ($c in $cands) {
        if ($c -and (Test-Path (Join-Path $c 'build-tools'))) { return $c }
    }
    throw "找不到 Android SDK（需要 build-tools 与 platforms）。请设置环境变量 ANDROID_HOME。"
}
$SDK = Resolve-Sdk

function Get-NewestDir($dir) {
    if (-not (Test-Path $dir)) { return $null }
    return (Get-ChildItem $dir -Directory | Sort-Object Name -Descending | Select-Object -First 1)
}

$btDir = Get-NewestDir (Join-Path $SDK 'build-tools')
if (-not $btDir) { throw "build-tools 为空：$SDK\build-tools" }
$BT = $btDir.FullName

# android.jar：取版本号最大的 platforms/android-*
$JAR = $null
$platDir = Join-Path $SDK 'platforms'
if (Test-Path $platDir) {
    foreach ($d in (Get-ChildItem $platDir -Directory | Sort-Object Name -Descending)) {
        $cand = Join-Path $d.FullName 'android.jar'
        if (Test-Path $cand) { $JAR = $cand; break }
    }
}
if (-not $JAR) { throw "找不到 platforms/android-*/android.jar" }

# JDK：优先 JAVA_HOME，其次常见安装位置
function Resolve-Jdk {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        return $env:JAVA_HOME
    }
    $roots = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'),
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Java',
        'C:\Program Files\Microsoft'
    )
    foreach ($r in $roots) {
        if (-not (Test-Path $r)) { continue }
        $d = Get-ChildItem $r -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
        if ($d -and (Test-Path (Join-Path $d.FullName 'bin\javac.exe'))) { return $d.FullName }
    }
    throw "找不到 JDK（需要 javac / jar / keytool）。请设置 JAVA_HOME 指向 JDK 目录。"
}
$env:JAVA_HOME = Resolve-Jdk
$JAVAC = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$JARTOOL = Join-Path $env:JAVA_HOME 'bin\jar.exe'
$KEYTOOL = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

$OUT = Join-Path $ROOT "build"
$KS = Join-Path $ROOT "keystore.jks"
$APK = Join-Path $ROOT "PointPic.apk"

Write-Host "SDK : $SDK"
Write-Host "BT  : $BT"
Write-Host "JAR : $JAR"
Write-Host "JDK : $env:JAVA_HOME"

Write-Host "== 0. 清理 ==" -ForegroundColor Cyan
if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Path $OUT, "$OUT\gen", "$OUT\classes", "$OUT\stubcls", "$OUT\dex" -Force | Out-Null

Write-Host "== 1. aapt2 compile ==" -ForegroundColor Cyan
& "$BT\aapt2.exe" compile --dir "$ROOT\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== 2. aapt2 link ==" -ForegroundColor Cyan
& "$BT\aapt2.exe" link -o "$OUT\base.apk" -I $JAR --manifest "$ROOT\AndroidManifest.xml" `
    -A "$ROOT\assets" --java "$OUT\gen" --min-sdk-version 29 --target-sdk-version 34 "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== 3. javac stubs ==" -ForegroundColor Cyan
$stubFiles = Get-ChildItem "$ROOT\stub" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $JAVAC -nowarn -source 8 -target 8 -encoding UTF-8 -cp $JAR -d "$OUT\stubcls" @stubFiles
if ($LASTEXITCODE -ne 0) { throw "javac stubs failed" }

Write-Host "== 4. javac app ==" -ForegroundColor Cyan
$srcFiles = @()
$srcFiles += (Get-ChildItem "$ROOT\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcFiles += (Get-ChildItem "$OUT\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& $JAVAC -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$JAR;$OUT\stubcls" -d "$OUT\classes" @srcFiles
if ($LASTEXITCODE -ne 0) { throw "javac app failed" }

Write-Host "== 5. jar + d8 ==" -ForegroundColor Cyan
& $JARTOOL cf "$OUT\classes.jar" -C "$OUT\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& "$BT\d8.bat" --release --min-api 29 --lib $JAR --output "$OUT\dex" "$OUT\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
if (-not (Test-Path "$OUT\dex\classes.dex")) { throw "classes.dex not produced" }

Write-Host "== 6. 打包 dex ==" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\base.apk", 'Update')
$existing = $zip.GetEntry('classes.dex')
if ($existing) { $existing.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$OUT\dex\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()

Write-Host "== 7. zipalign ==" -ForegroundColor Cyan
& "$BT\zipalign.exe" -f -p 4 "$OUT\base.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "== 8. keystore ==" -ForegroundColor Cyan
if (-not (Test-Path $KS)) {
    $ktlog = Join-Path $OUT "keytool.log"
    cmd /c "`"$KEYTOOL`" -genkeypair -keystore `"$KS`" -alias pickpic -keyalg RSA -keysize 2048 -validity 36500 -storepass android -keypass android -dname `"CN=PointPic, O=Wanan (dsh), C=CN`" > `"$ktlog`" 2>&1"
    if (Test-Path $ktlog) { Get-Content $ktlog | Select-Object -Last 3 }
}
if (-not (Test-Path $KS)) { throw "keystore not created" }

Write-Host "== 9. apksigner ==" -ForegroundColor Cyan
if (Test-Path $APK) { Remove-Item $APK -Force }
& "$BT\apksigner.bat" sign --ks $KS --ks-key-alias pickpic `
    --ks-pass pass:android --key-pass pass:android `
    --min-sdk-version 29 --out $APK "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "== 10. 校验 ==" -ForegroundColor Cyan
& "$BT\apksigner.bat" verify --print-certs $APK
Get-Item $APK | Select-Object FullName, Length, LastWriteTime | Format-List
Write-Host "构建完成: $APK" -ForegroundColor Green
