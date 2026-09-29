param(
    [Parameter(Mandatory=$true)][string]$Java,
    [Parameter(Mandatory=$true)][string]$JenkinsHome,
    [Parameter(Mandatory=$true)][string]$WarDir,
    [Parameter(Mandatory=$true)][string]$JenkinsWar
)
$ErrorActionPreference='Stop'
$repository=Split-Path -Parent $PSScriptRoot
$runtime=New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ('release-test-deps-'+[guid]::NewGuid().ToString('N')))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($JenkinsWar)
try {
    [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('executable/winstone.jar'),(Join-Path $runtime.FullName 'winstone.jar'))
} finally { $zip.Dispose() }
$classpath=@((Join-Path $WarDir 'WEB-INF\lib\*'),(Join-Path $runtime.FullName 'winstone.jar'),(Join-Path $repository 'src')) +
    @(Get-ChildItem -LiteralPath (Join-Path $JenkinsHome 'plugins') -File -Recurse -Filter '*.jar' |
      Where-Object {$_.FullName -match '\\WEB-INF\\lib\\'} | Select-Object -ExpandProperty FullName)
try {
    foreach($test in @('compileAll','runtime','parameterSchema','projectJobs','sourceChoices','codeSplitParameters','globalSecrets','gitEnvironment','projectSecrets','platformAdapters','loginDiagnostics','cpsExecution','pipelineFlow')) {
        & $Java '-Dfile.encoding=UTF-8' '-Dgroovy.source.encoding=UTF-8' -cp ($classpath -join ';') groovy.ui.GroovyMain (Join-Path $PSScriptRoot ($test+'.groovy')) $repository
        if($LASTEXITCODE -ne 0){throw "Test failed: $test"}
    }
    Write-Output 'ALL PASS: no Jenkins job, Unity process, upload or notification triggered.'
} finally {
    # 只清理本次明确创建的依赖文件和空目录，不递归清理系统临时目录。
    Remove-Item -LiteralPath (Join-Path $runtime.FullName 'winstone.jar')
    Remove-Item -LiteralPath $runtime.FullName
}
