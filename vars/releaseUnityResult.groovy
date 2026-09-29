/** Unity 输出契约：不解析 ProjectSettings，不推测 SDK 产物目录。 */
def clear() {
    java.nio.file.Files.deleteIfExists(new File(pwd(), 'release-build-result.json').toPath())
    java.nio.file.Files.deleteIfExists(new File(pwd(), 'release-publication.json').toPath())
}

def read(Map request) {
    def result = readJSON file:'release-build-result.json', returnPojo: true
    if (result.schemaVersion != 1 || result.succeeded != true || result.cdnUploaded != true)
        error 'Unity 未完成要求的构建/CDN 上传，或包版本不支持结果接口'
    ['buildType','platform','environment','coreVersion','planVersion','debug'].each { key ->
        if (result[key] != request[key]) error "Unity 结果与请求不一致: ${key}"
    }
    if (result.buildTarget != 'MiniGame') error 'Unity BuildTarget 与请求不一致'
    if (!result.aotBackupPath || !new File(result.aotBackupPath).isAbsolute() ||
        new File(result.aotBackupPath).canonicalFile != new File(request.aotBackupPath.toString()).canonicalFile)
        error 'Unity AOT 结果与指定路径不一致'
    if (request.player) {
        if (!result.packageDirectory || !new File(result.packageDirectory).isAbsolute() ||
            !new File(result.packageDirectory, 'game.js').isFile())
            error 'Unity 未返回有效的平台包目录'
    } else if (result.packageDirectory) {
        error '本次未请求平台包，Unity 却返回了平台产物'
    }
    result
}
