import groovy.json.JsonSlurperClassic

def action = binding.hasVariable('ACTION') ? (ACTION?.toString() ?: '') : ''
def platform = binding.hasVariable('PLATFORM') ? (PLATFORM?.toString() ?: '') : ''
def allowedPlatforms = @@ALLOWED_PLATFORMS@@
if (!(platform in allowedPlatforms)) {
    return ['没有可用原包：请先选择有效平台']
}

def requiredStatesByAction = [
    '启动代码分包采集': ['SOURCE_READY'],
    '再次启动代码分包采集': ['UPLOADED'],
    '查看采集状态': ['COLLECTING', 'UPLOADED'],
    '刷新采集码': ['COLLECTING'],
    '生成正式分包并上传': ['COLLECTING']
]
def requiredStates = requiredStatesByAction[action]
if (!requiredStates) {
    return ['没有可用原包：请选择有效操作']
}

def farmRoot = new File(@@BUILD_FARM_ROOT@@).canonicalFile
// 固定到生成 Job 的配置；异步联动不依赖请求所属 Job。
def sourceProjectName = @@SOURCE_PROJECT_NAME@@
if (!(sourceProjectName ==~ /[A-Za-z0-9][A-Za-z0-9_.-]*/)) {
    return ['SOURCE 配置错误：项目标识非法']
}
def projectRoot = new File(farmRoot, sourceProjectName).canonicalFile
if (!projectRoot.path.toLowerCase().startsWith(
    farmRoot.path.toLowerCase() + File.separator)) {
    return ['SOURCE 配置错误：项目目录越界']
}
def platformDir = new File(new File(projectRoot, 'ReleaseSources'), platform)
if (!platformDir.isDirectory()) {
    return ['没有可用原包：' + platform + ' 尚无 FullBuild 快照']
}

def environmentLabels = [
    Dev: '开发（Dev）',
    Test: '测试（Test）',
    Prod: '生产（Prod）'
]
def found = []
platformDir.eachDir { versionDir ->
    versionDir.eachDir { buildDir ->
        def stateFile = new File(buildDir, 'code-split-state.json')
        if (stateFile.isFile()) {
            try {
                def state = new JsonSlurperClassic().parse(stateFile)
                if (state.projectName == sourceProjectName &&
                    state.platform == platform && (state.state in requiredStates)) {
                    def environment = state.buildEnvironment == null ?
                        '未知' : state.buildEnvironment.toString()
                    def environmentLabel = environmentLabels[environment] ?: environment
                    def debugLabel = state.debug ? '是' : '否'
                    def updatedAt = state.updatedAt ?: state.createdAt ?: '未知'
                    found << [
                        label: [
                            state.platform,
                            state.version,
                            state.sourceBuildNumber,
                            state.state,
                            '环境=' + environmentLabel,
                            'Debug=' + debugLabel,
                            '更新=' + updatedAt
                        ].join('|'),
                        updated: stateFile.lastModified()
                    ]
                }
            } catch (ignored) {
                // 单个损坏状态文件不影响其他原包展示。
            }
        }
    }
}
if (!found) {
    return ['没有可用原包：' + platform + ' 当前操作要求状态 ' + requiredStates.join('/')]
}
return found.sort { a, b -> b.updated <=> a.updated }.collect { it.label }
