param(
    [string]$PyCharmDir = "D:\PyCharm"
)

$ErrorActionPreference = "Stop"
$projectDir = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$buildDir = Join-Path $projectDir "build"
$classesDir = Join-Path $buildDir "classes"
$testClassesDir = Join-Path $buildDir "test-classes"
$stageDir = Join-Path $buildDir "stage"
$pluginStageDir = Join-Path $stageDir "PythonChineseErrorHelper"
$pluginLibDir = Join-Path $pluginStageDir "lib"
$distributionDir = Join-Path $projectDir "dist"
$pluginJar = Join-Path $pluginLibDir "python-chinese-error-helper.jar"
$pluginZip = Join-Path $distributionDir "PythonChineseErrorHelper-2.1.0.zip"
$javaCompiler = Join-Path $PyCharmDir "jbr\bin\javac.exe"
$javaRuntime = Join-Path $PyCharmDir "jbr\bin\java.exe"

if (-not (Test-Path -LiteralPath $javaCompiler)) {
    throw "没有找到 PyCharm 自带的 Java 编译器：$javaCompiler"
}

foreach ($target in @($buildDir, $distributionDir)) {
    $absoluteTarget = [System.IO.Path]::GetFullPath($target)
    if (-not $absoluteTarget.StartsWith($projectDir, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝清理项目目录之外的路径：$absoluteTarget"
    }
    if (Test-Path -LiteralPath $absoluteTarget) {
        Remove-Item -LiteralPath $absoluteTarget -Recurse -Force
    }
}

New-Item -ItemType Directory -Path $classesDir, $testClassesDir, $pluginLibDir, $distributionDir | Out-Null

$mainSources = Get-ChildItem -LiteralPath (Join-Path $projectDir "src\main\java") -Filter "*.java" -Recurse |
    ForEach-Object { $_.FullName }
$platformClasspath = Join-Path $PyCharmDir "lib\*"
& $javaCompiler --release 21 -encoding UTF-8 -classpath $platformClasspath -d $classesDir $mainSources
if ($LASTEXITCODE -ne 0) {
    throw "插件源码编译失败"
}

Copy-Item -Path (Join-Path $projectDir "src\main\resources\*") -Destination $classesDir -Recurse -Force

$testSources = Get-ChildItem -LiteralPath (Join-Path $projectDir "src\test\java") -Filter "*.java" -Recurse |
    ForEach-Object { $_.FullName }
& $javaCompiler --release 21 -encoding UTF-8 -classpath $classesDir -d $testClassesDir $testSources
if ($LASTEXITCODE -ne 0) {
    throw "测试源码编译失败"
}
& $javaRuntime -ea -classpath "$classesDir;$testClassesDir" com.codex.pyerrorhelper.PythonErrorAnalyzerSelfTest
if ($LASTEXITCODE -ne 0) {
    throw "报错分析测试失败"
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function New-StandardZip {
    param(
        [Parameter(Mandatory = $true)][string]$SourceDirectory,
        [Parameter(Mandatory = $true)][string]$DestinationFile,
        [bool]$ManifestFirst = $false
    )

    $sourcePath = [System.IO.Path]::GetFullPath($SourceDirectory).TrimEnd('\')
    $stream = [System.IO.File]::Open($DestinationFile, [System.IO.FileMode]::CreateNew)
    try {
        $archive = New-Object System.IO.Compression.ZipArchive(
            $stream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $false,
            [System.Text.Encoding]::UTF8
        )
        try {
            $addedEntries = New-Object 'System.Collections.Generic.HashSet[string]'

            if ($ManifestFirst) {
                [void]$archive.CreateEntry('META-INF/')
                [void]$addedEntries.Add('META-INF/')
                $manifestPath = Join-Path $sourcePath 'META-INF\MANIFEST.MF'
                if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
                    throw "缺少标准 JAR 清单：$manifestPath"
                }
                [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $archive,
                    $manifestPath,
                    'META-INF/MANIFEST.MF',
                    [System.IO.Compression.CompressionLevel]::Optimal
                )
                [void]$addedEntries.Add('META-INF/MANIFEST.MF')
            }

            Get-ChildItem -LiteralPath $sourcePath -Directory -Recurse | ForEach-Object {
                $relative = $_.FullName.Substring($sourcePath.Length + 1).Replace('\', '/') + '/'
                if ($addedEntries.Add($relative)) {
                    [void]$archive.CreateEntry($relative)
                }
            }

            Get-ChildItem -LiteralPath $sourcePath -File -Recurse | ForEach-Object {
                $relative = $_.FullName.Substring($sourcePath.Length + 1).Replace('\', '/')
                if ($addedEntries.Add($relative)) {
                    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                        $archive,
                        $_.FullName,
                        $relative,
                        [System.IO.Compression.CompressionLevel]::Optimal
                    )
                }
            }
        }
        finally {
            if ($null -ne $archive) { $archive.Dispose() }
        }
    }
    finally {
        $stream.Dispose()
    }
}

New-StandardZip -SourceDirectory $classesDir -DestinationFile $pluginJar -ManifestFirst $true
New-StandardZip -SourceDirectory $stageDir -DestinationFile $pluginZip

Write-Host "构建完成：$pluginZip"
