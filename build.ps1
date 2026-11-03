$ErrorActionPreference = 'Stop'

$ROOT = "C:\Users\Administrator\Downloads\20261103\picker"
$SDK  = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$BT   = Join-Path $SDK "build-tools\36.0.0"
$JAR  = Join-Path $SDK "platforms\android-36.1\android.jar"
$OUT  = Join-Path $ROOT "build"
$KS   = Join-Path $ROOT "keystore.jks"
$APK  = Join-Path $ROOT "PickPic.apk"

$env:JAVA_HOME = "C:\Users\Administrator\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
$JAVAC = Join-Path $env:JAVA_HOME "bin\javac.exe"
$JARTOOL = Join-Path $env:JAVA_HOME "bin\jar.exe"
$KEYTOOL = Join-Path $env:JAVA_HOME "bin\keytool.exe"

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
    cmd /c "`"$KEYTOOL`" -genkeypair -keystore `"$KS`" -alias pickpic -keyalg RSA -keysize 2048 -validity 36500 -storepass android -keypass android -dname `"CN=PickPic, OU=DSH, O=DSH, L=NA, S=NA, C=CN`" > `"$ktlog`" 2>&1"
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
