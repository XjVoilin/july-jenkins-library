import groovy.json.JsonSlurperClassic

def farmRoot = new File(@@BUILD_FARM_ROOT@@).canonicalFile
def allowedPlatforms = @@ALLOWED_PLATFORMS@@
def allowedStates = ['SOURCE_READY', 'COLLECTING', 'UPLOADED']
def environmentLabels = [Dev: '开发（Dev）', Test: '测试（Test）', Prod: '生产（Prod）']
def found = []
// 固定到生成 Job 的配置，不依赖 Active Choices 的请求上下文。
def sourceProjectName = @@SOURCE_PROJECT_NAME@@
def sourceCoreVersion = @@SOURCE_CORE_VERSION@@
if (!(sourceProjectName ==~ /[A-Za-z0-9][A-Za-z0-9_.-]*/) ||
    !(sourceCoreVersion ==~ /[0-9]+[.][0-9]+[.][0-9]+/)) {
    return ['SOURCE 配置错误：项目标识或核心版本非法']
}
def activeProjectRoot = new File(farmRoot, sourceProjectName).canonicalFile
if (!activeProjectRoot.path.toLowerCase().startsWith(farmRoot.path.toLowerCase() + File.separator)) {
    return ['SOURCE 配置错误：项目目录越界']
}
def root = new File(activeProjectRoot, 'ReleaseSources')

allowedPlatforms.each { platform ->
    def versionDir = new File(new File(root, platform), sourceCoreVersion)
    if (!versionDir.isDirectory()) return
    versionDir.eachDir { buildDir ->
        def stateFile = new File(buildDir, 'code-split-state.json')
        if (!stateFile.isFile()) return
        try {
            def state = new JsonSlurperClassic().parse(stateFile)
            def aotDir = state.aotBackupPath == null ? null : new File(state.aotBackupPath.toString())
            if (state.schemaVersion == 6 && state.buildTarget == 'MiniGame' &&
                state.projectName == sourceProjectName &&
                state.platform == platform && state.version == sourceCoreVersion &&
                state.sourceBuildNumber.toString() == buildDir.name &&
                state.state in allowedStates && aotDir?.isDirectory() && state.aotBackupFingerprint &&
                aotDir.canonicalFile == new File(buildDir, 'aot').canonicalFile) {
                def environment = state.buildEnvironment == null ? '未知' : state.buildEnvironment.toString()
                def environmentLabel = environmentLabels[environment] ?: environment
                def debugLabel = state.debug ? '是' : '否'
                def commit = state.sourceGitCommit == null ? '未知' : state.sourceGitCommit.toString()
                def updatedAt = state.updatedAt ?: state.createdAt ?: '未知'
                found << [
                    label: [platform, state.version, state.sourceBuildNumber, state.state,
                            '环境=' + environmentLabel, 'Debug=' + debugLabel,
                            'Commit=' + commit.take(8), '更新=' + updatedAt].join('|'),
                    updated: stateFile.lastModified()
                ]
            }
        } catch (ignored) {
            // 单个损坏的 Source 不影响其他选项。
        }
    }
}

if (!found) return ['没有可用 Source：请先执行当前项目的 FullBuild']
return ['请选择 Source（必选）'] +
    found.sort { a, b -> b.updated <=> a.updated }.collect { it.label }
