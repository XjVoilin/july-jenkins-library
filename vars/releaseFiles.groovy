/** 共享构建模块：releaseFiles。仅由流水线调用。 */
def extractWasmMd5(String packageDir) {
    def output = bat(script: "@dir /b \"${packageDir}\\wasmcode\\*.wasm*\" 2>nul", returnStdout: true).trim()
    def matcher = (output =~ /([0-9a-f]{16,32})/)
    return matcher ? matcher[0][1] : ''
}

def runCheckedBat(String label, String command, List<String> requiredMarkers = []) {
    def output = bat(script: "@${command}", returnStdout: true).trim()
    if (output) { echo output }
    return validateCliOutput(label, output, requiredMarkers)
}

/** 非零退出时仍读取 stdout/stderr；调用方必须在 withCredentials 内执行。 */
def runDiagnosedBat(String label, String command, List<String> secrets = []) {
    def logDir = "${pwd(tmp: true)}/cli-${java.util.UUID.randomUUID()}"
    def logFile = "${logDir}/output.log"
    long startedAt = System.currentTimeMillis()
    echo "[${label}] 开始执行（stdout/stderr 已捕获，账号凭据脱敏）"
    try {
        writeFile(file: logFile, text: '', encoding: 'UTF-8')
        int exitCode = bat(label: label, returnStatus: true, encoding: 'UTF-8',
            script: "@${command} > \"${logFile}\" 2>&1")
        def output = readFile(file: logFile, encoding: 'UTF-8').trim()
        output = redactCliOutput(output, secrets)
        echo output ?: "[${label}] CLI 未输出任何内容"
        def hexCode = Integer.toHexString(exitCode).toUpperCase().padLeft(8, '0')
        echo "[${label}] exitCode=${exitCode} (0x${hexCode}), elapsedMs=${System.currentTimeMillis() - startedAt}"
        if (exitCode != 0) {
            error "[${label}] 命令失败，退出码 ${exitCode} (0x${hexCode})；请查看上方 CLI 输出"
        }
        return validateCliOutput(label, output, [])
    } finally {
        // 原始输出可能含凭据，不归档；仅清理本次创建的专属临时目录。
        dir(logDir) { deleteDir() }
    }
}

@com.cloudbees.groovy.cps.NonCPS
private String redactCliOutput(String output, List<String> secrets) {
    secrets.findAll { it }.sort { a, b -> b.length() <=> a.length() }.each { secret ->
        output = output.replace(secret, '****')
    }
    return output
}

private def validateCliOutput(String label, String output, List<String> requiredMarkers) {
    if (output =~ /(?im)(\bfailed\b|Upload Error:|(?:^|\s)Error:|找不到|失败)/) {
        error "[${label}] CLI 输出包含失败信息；已阻止读取旧状态或继续上传"
    }
    requiredMarkers.each { marker ->
        if (!output.contains(marker)) { error "[${label}] 未检测到成功标记: ${marker}" }
    }
    return output
}

def mirrorDirectory(String sourceDir, String targetDir, String allowedRoot) {
    def canonicalRoot = new File(allowedRoot).canonicalPath
    def canonicalTarget = new File(targetDir).canonicalPath
    if (!canonicalTarget.toLowerCase().startsWith(canonicalRoot.toLowerCase() + File.separator)) {
        error "[ReleaseSource] 非法原包快照目录: ${canonicalTarget}"
    }
    robocopyMirror(sourceDir, canonicalTarget)
}

private def robocopyMirror(String sourceDir, String targetDir) {
    def source = new File(sourceDir).canonicalFile
    def target = new File(targetDir).canonicalFile
    if (!source.isDirectory() || source == target ||
        source.toPath().startsWith(target.toPath()) || target.toPath().startsWith(source.toPath())) {
        error "[Files] 镜像源缺失或源目标重叠: $source -> $target"
    }
    bat """@echo off
if exist "${targetDir}" rmdir /s /q "${targetDir}"
robocopy "${sourceDir}" "${targetDir}" /MIR /R:2 /W:2 /NFL /NDL /NJH /NJS /NP
set "RC=%ERRORLEVEL%"
if %RC% GEQ 8 exit /b %RC%
exit /b 0"""
}

def directoryFingerprint(String packageDir) {
    if (!new File(packageDir).isDirectory()) error "[Fingerprint] 目录不存在: $packageDir"
    def escaped = packageDir.replace("'", "''")
    return powershell(returnStdout: true, script: """
        \$ErrorActionPreference = 'Stop'
        \$root = [IO.Path]::GetFullPath('${escaped}').TrimEnd('\\')
        \$lines = Get-ChildItem -LiteralPath \$root -Recurse -File | Sort-Object FullName | ForEach-Object {
            \$relative = \$_.FullName.Substring(\$root.Length).TrimStart('\\')
            \$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath \$_.FullName).Hash
            "\$relative|\$(\$_.Length)|\$hash"
        }
        \$sha = [Security.Cryptography.SHA256]::Create()
        try {
            \$bytes = [Text.Encoding]::UTF8.GetBytes((\$lines -join "`n"))
            ([BitConverter]::ToString(\$sha.ComputeHash(\$bytes))).Replace('-', '').ToLowerInvariant()
        } finally {
            \$sha.Dispose()
        }
    """).trim()
}

def mirrorManagedDirectory(String sourceDir, String targetDir, String purpose,
                            String allowedRoot) {
    def canonicalRoot = new File(allowedRoot).canonicalPath
    def canonicalTarget = new File(targetDir).canonicalPath
    def lowerTarget = canonicalTarget.toLowerCase()
    if (!(purpose in ['collection', 'release']) ||
        !lowerTarget.startsWith(canonicalRoot.toLowerCase() + File.separator) ||
        !lowerTarget.contains("\\${purpose}\\") ||
        !(lowerTarget.endsWith('\\minigame') || lowerTarget.endsWith('\\tt-minigame'))) {
        error "[CodeSplit] 非法 ${purpose} 目录: ${canonicalTarget}"
    }
    robocopyMirror(sourceDir, canonicalTarget)
}
