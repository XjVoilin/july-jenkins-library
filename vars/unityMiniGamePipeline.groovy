import org.july.release.ReleaseStorage

/**
 * Unity 小游戏固定 Item 构建流水线
 *
 * 每个 CoreVersion 对应 2 个 Jenkins Item。新版本推荐通过 unityMiniGameVersionJobs 生成，避免手工修改多处版本号。
 *
 *   全量构建:
 *   @Library('unity-minigame') _
 *   unityMiniGamePipeline(
 *       projectName: 'GooseMarket',
 *       displayName: '大鹅超市 v1.3.0 全量',
 *       buildClass:  'July.Release.Editor.BuildPipelineCI',
 *       platforms:   ['WeChat', 'TikTok'],
 *       repoUrl:     'git@your-server:GooseMarket.git',
 *       coreVersion: '1.3.0',
 *       buildType:   'FullBuild',
 *   )
 *
 *   热更构建:
 *   @Library('unity-minigame') _
 *   unityMiniGamePipeline(
 *       projectName: 'GooseMarket',
 *       displayName: '大鹅超市 v1.3.0 热更',
 *       buildClass:  'July.Release.Editor.BuildPipelineCI',
 *       platforms:   ['WeChat', 'TikTok'],
 *       repoUrl:     'git@your-server:GooseMarket.git',
 *       coreVersion: '1.3.0',
 *       buildType:   'HotUpdateBuild',
 *   )
 *
 * 可选参数:
 *   unityPath    - Unity/Tuanjie 可执行文件路径
 *   agentLabel   - Agent 标签 (默认 'unity')
 *   branchPrefix - 分支名前缀 (默认 'Tuanjie_Build/')
 *   preserveDirs - git clean 时保留的目录列表 (默认 ['Library/', 'HybridCLRData/'])
 *   extraParams  - 额外参数定义列表
 *   fixedParams  - 锁定参数 Map（如 [ENV:'Test', DEBUG:true]），锁定的参数不出现在 Jenkins UI
 *   extraArgs         - 传给 Unity 的额外命令行参数闭包 { -> [] }
 *   unityProxyUrl     - Unity/UPM 使用的 HTTP 代理（默认 http://127.0.0.1:7890）
 *   buildFarmRoot     - 多项目构建根目录（默认 D:\\BuildFarm）
 *   credentialsFile  - 项目明文配置文件（默认 <buildFarmRoot>\\<projectName>\\Secrets\\credentials.local.json）
 */
def call(Map config) {

    assert config.projectName : 'projectName is required'
    assert config.displayName : 'displayName is required'
    assert config.buildClass  : 'buildClass is required'
    assert config.platforms   : 'platforms is required (list of platform choices)'
    assert config.repoUrl     : 'repoUrl is required (git clone URL)'
    assert config.coreVersion : 'coreVersion is required (e.g. "1.3.0")'
    assert config.buildType in ['FullBuild', 'HotUpdateBuild'] :
        "buildType must be 'FullBuild' or 'HotUpdateBuild'"

    def projectName = config.projectName.toString()

    def coreVersion = config.coreVersion.toString()
    def buildType   = config.buildType.toString()
    def isHotUpdate = (buildType == 'HotUpdateBuild')
    def repoUrl     = config.repoUrl.toString()

    def agentLabel   = config.agentLabel   ?: 'unity'
    def unityPath    = config.unityPath    ?: 'D:\\Install\\UnityAll\\Unity\\Editor\\2022.3.61t9\\Editor\\Tuanjie.exe'
    def branchPrefix = config.branchPrefix ?: 'Tuanjie_Build/'
    def preserveDirs = config.preserveDirs ?: ['Library/', 'HybridCLRData/']
    def extraParams       = config.extraParams       ?: []
    def fixedParams       = config.fixedParams       ?: [:]
    def savedParamsFile   = '.jenkins_saved_params'
    def unityProxyUrl     = config.unityProxyUrl ?: 'http://127.0.0.1:7890'
    def unityProxyEnv     = [
        "HTTP_PROXY=${unityProxyUrl}",
        "HTTPS_PROXY=${unityProxyUrl}",
        'NO_PROXY=localhost,127.0.0.1',
    ]

    def project = releaseProject.load(config)
    def projectLease = null
    def buildFarmRoot = project.farm.path
    def projectRootFile = project.root
    def projectRoot = project.root.path
    def projectWorkspace = project.workspace
    def projectBuildRoot = project.build
    def releaseSourceRoot = project.sources
    def codeSplitCacheRoot = project.splitCache
    def projectSecretsRoot = project.secrets
    def credentialsPath = project.credentialsFile
    def localCredentials = project.credentials
    def wechatConfig = localCredentials.wechat instanceof Map ? localCredentials.wechat : [:]
    def feishuConfig = localCredentials.feishu instanceof Map ? localCredentials.feishu : [:]
    def cosConfig = localCredentials.cos instanceof Map ? localCredentials.cos : [:]
    def localWechatAppId = (wechatConfig.appId ?: '').toString()
    def localWechatUploadKeyPath = (wechatConfig.uploadKeyPath ?: '').toString()
    def uploadRobot = releaseCredentials.optionalPositiveInteger(wechatConfig.uploadRobot, 'wechat.uploadRobot')
    def feishuEnabled = feishuConfig.enabled as boolean
    def localFeishuWebhook = (feishuConfig.webhook ?: '').toString()
    def localCosConfigPath = (cosConfig.configPath ?: '').toString()

    def commonRequiredFields = [
        'cos.configPath': localCosConfigPath,
    ]
    if (feishuEnabled) {
        commonRequiredFields['feishu.webhook'] = localFeishuWebhook
    }
    releaseCredentials.requireProjectFields(commonRequiredFields, credentialsPath.canonicalPath)

    def hotUpdateSourceScript = isHotUpdate ? unityMiniGameSourceScript(
        kind: 'hot-update', projectName: projectName, coreVersion: coreVersion,
        buildFarmRoot: buildFarmRoot, platforms: config.platforms) : ''

    def PARAM_DEFS = releaseParameters.definitions(isHotUpdate ? 'hot-update' : 'full-build',
        [platforms:config.platforms, coreVersion:coreVersion, sourceScript:hotUpdateSourceScript])

    PARAM_DEFS += extraParams

    // fixedParams: 从 UI 移除被锁定的参数，p() 会直接返回锁定值
    def p = { String name -> releaseParameters.value(name, PARAM_DEFS, params, fixedParams) }
    // 保留完整 schema 校验锁定参数，仅在写入 UI 时过滤。
    def visibleParams = { PARAM_DEFS.findAll { !fixedParams.containsKey(it.name) } }

    pipeline {
        agent {
            node {
                label agentLabel
                customWorkspace "${projectWorkspace}"
            }
        }

        options { disableConcurrentBuilds() }

        environment {
            UNITY_PATH        = "${unityPath}"
            SAVED_PARAMS_FILE = "${savedParamsFile}"
        }

        stages {
            stage('PrepareBuildFarm') {
                steps {
                    script {
                        projectLease = ReleaseStorage.acquire(projectRootFile, env.BUILD_URL ?: env.BUILD_TAG)
                        releaseJenkinsSecrets.check('git')
                        // 提前验证固定/页面枚举，禁止非法值进入文件路径与命令。
                        p('PLATFORM')
                        if (!isHotUpdate) p('ENV')
                        if (!(coreVersion ==~ /\d+\.\d+\.\d+/)) error 'CoreVersion 非法'
                        [projectRoot, projectWorkspace, projectBuildRoot,
                         releaseSourceRoot, projectSecretsRoot.canonicalPath].each { path ->
                            bat "@if not exist \"${path}\" mkdir \"${path}\""
                        }
                        echo "[BuildFarm] 使用项目配置（账号密码由全局 Jenkins Secret text 注入）: ${credentialsPath}"
                        if (!isHotUpdate && p('BUILD_MINIGAME')) {
                            def platform = p('PLATFORM')
                            if (platform == 'WeChat') {
                                releaseCredentials.requireProjectFields([
                                    'wechat.appId': localWechatAppId,
                                    'wechat.uploadKeyPath': localWechatUploadKeyPath,
                                    'wechat.uploadRobot': uploadRobot,
                                ], credentialsPath.canonicalPath)
                                releaseCredentials.resolveProjectSecretFile(
                                    localWechatUploadKeyPath,
                                    projectSecretsRoot.canonicalPath,
                                    'wechat.uploadKeyPath')
                            } else if (platform == 'TikTok') {
                                releaseJenkinsSecrets.check('douyin')
                            }
                        }
                        releaseCredentials.resolveProjectSecretFile(
                            localCosConfigPath, projectSecretsRoot.canonicalPath, 'cos.configPath')
                        if (!isHotUpdate) {
                            env.RESERVED_SOURCE_ROOT = ReleaseStorage.reserve(new File(releaseSourceRoot),
                                p('PLATFORM').toString(), coreVersion, env.BUILD_NUMBER)
                            env.AOT_BACKUP_OUTPUT_PATH = ReleaseStorage.aotPath(new File(env.RESERVED_SOURCE_ROOT))
                        }
                    }
                }
            }

            stage('Clean') {
                steps {
                    script {
                        if (p('CLEAN_LIBRARY')) {
                            bat 'if exist Library rmdir /s /q Library'
                        }

                        def gitActions = {
                            if (!fileExists('.git')) {
                                echo 'Workspace 尚未初始化 Git，执行 git init...'
                                bat 'git init .'
                            }

                            def hasOrigin = bat(
                                script: '@git remote get-url origin >nul 2>&1',
                                returnStatus: true
                            ) == 0
                            if (hasOrigin) {
                                bat "git remote set-url origin \"${repoUrl}\""
                            } else {
                                bat "git remote add origin \"${repoUrl}\""
                            }

                            def hasHead = bat(
                                script: '@git rev-parse --verify HEAD >nul 2>&1',
                                returnStatus: true
                            ) == 0
                            if (hasHead) {
                                bat 'git reset --hard HEAD'
                            }

                            bat 'git fetch origin --tags'
                            bat "git checkout -B ${branchPrefix}${coreVersion} origin/${branchPrefix}${coreVersion}"
                            bat "git reset --hard origin/${branchPrefix}${coreVersion}"
                        }
                        releaseCredentials.withGitAuthentication(gitActions)

                        def excludes = (preserveDirs + [savedParamsFile]).collect { "-e ${it}" }.join(' ')
                        bat "git clean -fdx ${excludes}"

                        def configuredCosFile = releaseCredentials.resolveProjectSecretFile(
                            localCosConfigPath,
                            projectSecretsRoot.canonicalPath,
                            'cos.configPath')
                        bat '@if not exist "Tools\\coscli" mkdir "Tools\\coscli"'
                        bat "@copy /y \"${configuredCosFile}\" \"Tools\\coscli\\.cos.yaml\" >nul"
                        echo '[BuildFarm] 已应用项目级 COS 配置'
                    }
                }
            }

            stage('Validate') {
                when { expression { isHotUpdate } }
                steps {
                    script {
                        def selectedSource = "${params.SOURCE ?: ''}".trim()
                        if (!selectedSource || selectedSource.startsWith('请选择 Source') ||
                            selectedSource.startsWith('没有可用 Source') ||
                            selectedSource.startsWith('SOURCE 读取失败')) {
                            error "请选择有效的热更 SOURCE；当前值：${selectedSource ?: '空'}"
                        }
                        def parts = selectedSource.split('\\|', -1).collect { it.trim() }
                        if (parts.size() < 4) { error "SOURCE 格式非法: ${selectedSource}" }
                        def sourcePlatform = parts[0]
                        def sourceVersion = parts[1]
                        def sourceBuildNumber = parts[2]
                        def selectedState = parts[3]
                        if (!(sourcePlatform in config.platforms) || sourceVersion != coreVersion) {
                            error "SOURCE 与当前热更 Job 不匹配: ${selectedSource}"
                        }
                        if (!(sourceBuildNumber ==~ /\d+/)) {
                            error "SOURCE 构建号非法: ${sourceBuildNumber}"
                        }
                        def sourceRoot = "${releaseSourceRoot}\\${sourcePlatform}\\${sourceVersion}\\${sourceBuildNumber}"
                        def stateFile = "${sourceRoot}\\code-split-state.json"
                        if (!fileExists(stateFile)) { error "SOURCE 状态文件不存在: ${stateFile}" }
                        def sourceState = readJSON file: stateFile, returnPojo: true
                        if (sourceState.projectName != projectName ||
                            sourceState.platform != sourcePlatform || sourceState.version != sourceVersion ||
                            "${sourceState.sourceBuildNumber}" != sourceBuildNumber ||
                            sourceState.state != selectedState ||
                            !(sourceState.state in ['SOURCE_READY', 'COLLECTING', 'UPLOADED'])) {
                            error '[HotUpdateSource] 页面选项与状态文件不一致，请刷新参数页后重试'
                        }
                        def sourceEnvironment = (sourceState.buildEnvironment ?: '').toString()
                        if (!(sourceEnvironment in ['Dev', 'Test', 'Prod'])) {
                            error "SOURCE 构建环境非法: ${sourceEnvironment ?: '空'}"
                        }
                        if (sourceState.schemaVersion != 6 || sourceState.buildTarget != 'MiniGame') {
                            error 'SOURCE 不是框架显式路径接口生成的基线，请更新框架后重新全量构建'
                        }
                        def aotSnapshot = (sourceState.aotBackupPath ?: '').toString()
                        def aotFingerprint = (sourceState.aotBackupFingerprint ?: '').toString()
                        if (!aotSnapshot || !aotFingerprint || !fileExists(aotSnapshot)) {
                            error 'SOURCE 缺少不可变 AOT 快照，请使用新版 FullBuild 重新生成 Source'
                        }
                        aotSnapshot = ReleaseStorage.aotPath(new File(sourceRoot), aotSnapshot, true)
                        def actualAotFingerprint = releaseFiles.directoryFingerprint(aotSnapshot)
                        if (actualAotFingerprint != aotFingerprint) {
                            error "SOURCE AOT 指纹校验失败: state=${aotFingerprint}, actual=${actualAotFingerprint}"
                        }
                        def sourceCommit = (sourceState.sourceGitCommit ?: '').toString()
                        if (!(sourceCommit ==~ /[0-9a-fA-F]{40}/)) {
                            error "SOURCE Git Commit 非法: ${sourceCommit ?: '空'}"
                        }
                        def isAncestor = bat(
                            script: "@git merge-base --is-ancestor ${sourceCommit} HEAD",
                            returnStatus: true)
                        if (isAncestor != 0) {
                            error "当前热更分支不包含 SOURCE Commit ${sourceCommit}，拒绝跨基线构建"
                        }

                        fixedParams['PLATFORM'] = sourcePlatform
                        fixedParams['ENV'] = sourceEnvironment
                        fixedParams['DEBUG'] = sourceState.debug ? true : false
                        env.HOTUPDATE_SOURCE_BUILD = sourceBuildNumber
                        env.HOTUPDATE_SOURCE_STATE = stateFile
                        env.HOTUPDATE_SOURCE_COMMIT = sourceCommit
                        env.HOTUPDATE_AOT_SNAPSHOT = aotSnapshot
                        env.HOTUPDATE_AOT_FINGERPRINT = aotFingerprint

                        def pv = params.PLAN_VERSION?.trim()
                        if (!pv) {
                            error("PLAN_VERSION 不能为空（热更构建必须指定目标版本）")
                        }
                        if (_compareVersions(pv, coreVersion) < 0) {
                            error("PLAN_VERSION (${pv}) 不能小于 coreVersion (${coreVersion})")
                        }
                        echo "[HotUpdateSource] FullBuild #${sourceBuildNumber} | ${sourcePlatform} ${sourceVersion} | " +
                             "environment=${sourceEnvironment} | debug=${fixedParams.DEBUG} | " +
                             "commit=${env.HOTUPDATE_SOURCE_COMMIT}"
                    }
                }
            }

            stage('Guard') {
                when {
                    expression {
                        return (isHotUpdate || p('ENV') == 'Prod') && !p('FORCE_REBUILD')
                    }
                }
                steps {
                    script {
                        def planVersion = isHotUpdate ? p('PLAN_VERSION') : coreVersion
                        def platform = p('PLATFORM').toLowerCase()
                        def releaseEnvironment = "${p('ENV')}".toLowerCase()
                        def tagPattern = "release/${releaseEnvironment}/${platform}/*/${planVersion}"
                        def existing = bat(
                            script: "@git tag -l \"${tagPattern}\"",
                            returnStdout: true).trim()
                        if (existing) {
                            error("版本 ${planVersion} 已存在发布标签:\n${existing}\n\n" +
                                  "若需强制重建，请勾选 FORCE_REBUILD。")
                        }
                    }
                }
            }

            stage('SyncDefines') {
                when {
                    expression {
                        def settings = readFile('ProjectSettings/ProjectSettings.asset')
                        def platform = p('PLATFORM')
                        def platformDefine = platform == 'TikTok' ? 'JULYGF_DY_MINIGAME' : 'JULYGF_WX_MINIGAME'

                        // 只检查 WeixinMiniGame 行（Tuanjie BuildTargetGroup.MiniGame 对应的实际 target）
                        def lines = settings.split('\\n')
                        def activeLine = lines.find { it.trim().startsWith('WeixinMiniGame:') }
                        if (!activeLine) {
                            echo '[SyncDefines] WeixinMiniGame defines not found, will sync'
                            return true
                        }

                        def needsPlatform = !activeLine.contains(platformDefine)
                        def hasDebug = activeLine.contains('JULYGF_DEBUG')
                        def wantDebug = p('DEBUG') ? true : false
                        def needsDebug = (wantDebug != hasDebug)

                        if (!needsPlatform && !needsDebug) {
                            echo "[SyncDefines] Defines already match (${platformDefine}${wantDebug ? ' + JULYGF_DEBUG' : ''}), skipping"
                        }
                        return needsPlatform || needsDebug
                    }
                }
                steps {
                    script {
                        def args = [
                            "\"${unityPath}\"",
                            '-batchmode', '-quit', '-nographics',
                            '-logFile', 'sync_defines.log',
                            '-projectPath', "\"${WORKSPACE}\"",
                            '-buildTarget', 'MiniGame',
                            '-executeMethod', "${config.buildClass}.SyncPlatformDefines",
                            '-platform', p('PLATFORM'),
                        ]
                        if (p('DEBUG')) { args << '-debug' }

                        def cmd = args.join(' ')
                        echo "[SyncDefines] ${cmd}"

                        releaseCredentials.withGitAuthentication() {
                            withEnv(unityProxyEnv) { bat cmd }
                        }
                    }
                }
            }

            stage('Build') {
                steps {
                    script {
                        def planVersion = isHotUpdate ? p('PLAN_VERSION') : coreVersion
                        def method = "${config.buildClass}.${buildType}"

                        if (!isHotUpdate && p('BUILD_MINIGAME')) {
                            // 代码分包工具会原地写入 profile/release 文件。全量小游戏出包前必须清理
                            // 当前平台/版本目录，避免普通正式包混入上一次分包产物。
                            def buildRoot = new File(projectBuildRoot).canonicalPath
                            def exportDir = new File(
                                "${buildRoot}\\${p('PLATFORM')}\\${coreVersion}").canonicalPath
                            def allowedPrefix = buildRoot.toLowerCase() + File.separator
                            if (!exportDir.toLowerCase().startsWith(allowedPrefix)) {
                                error "[Build] 拒绝清理 Build 根目录之外的路径: ${exportDir}"
                            }
                            echo "[Build] 清理旧小游戏导出目录: ${exportDir}"
                            bat "@if exist \"${exportDir}\" rmdir /s /q \"${exportDir}\""
                        }

                        def args = [
                            "\"${unityPath}\"",
                            '-batchmode', '-quit', '-nographics',
                            '-logFile', 'build.log',
                            '-projectPath', "\"${WORKSPACE}\"",
                            '-buildTarget', 'MiniGame',
                            '-executeMethod', method,
                            '-platform', p('PLATFORM'),
                            '-planVersion', planVersion,
                            '-env', p('ENV'),
                        ]

                        if (p('DEBUG')) {
                            args << '-debug'
                        }
                        if (!isHotUpdate && p('BUILD_MINIGAME')) {
                            args << '-miniGame'
                        }
                        if (isHotUpdate) {
                            args << '-aotBackupInputPath'
                            args << "\"${env.HOTUPDATE_AOT_SNAPSHOT}\""
                            args << '-aotBackupVersion'
                            args << coreVersion
                        } else {
                            def output = ReleaseStorage.aotPath(new File(env.RESERVED_SOURCE_ROOT),
                                env.AOT_BACKUP_OUTPUT_PATH)
                            if (fileExists(output)) error 'AOT 输出目录已存在，拒绝覆盖已有快照'
                            args << '-aotBackupOutputPath'
                            args << "\"${output}\""
                        }

                        if (p('FORCE_REBUILD')) {
                            args << '-forceRebuild'
                            echo "[Build] FORCE_REBUILD=true: ${buildType}, ${p('ENV')}/${p('PLATFORM')}, CoreVersion=${coreVersion}, PlanVersion=${planVersion}; 已向 Unity 传递 -forceRebuild"
                        }

                        if (config.extraArgs) {
                            def extra = config.extraArgs()
                            if (extra.any { it.toString() =~ /(?i)(?:^|\s)["']?-aotBackup(?:InputPath|OutputPath|Version)["']?(?:\s|=|$)/ }) {
                                error 'extraArgs 禁止覆盖 Jenkins 管理的 AOT 路径或版本断言'
                            }
                            args.addAll(extra)
                        }

                        def cmd = args.join(' ')
                        echo "执行命令: ${cmd}"

                        releaseCredentials.withGitAuthentication() {
                            withEnv(unityProxyEnv) { bat cmd }
                        }
                    }
                }
            }

            stage('VerifyAotSnapshot') {
                steps {
                    script {
                        if (isHotUpdate) {
                            def sourceRoot = new File(env.HOTUPDATE_SOURCE_STATE).parentFile
                            def input = ReleaseStorage.aotPath(sourceRoot, env.HOTUPDATE_AOT_SNAPSHOT, true)
                            if (releaseFiles.directoryFingerprint(input) != env.HOTUPDATE_AOT_FINGERPRINT) {
                                error '框架热更后输入 AOT 快照发生变化，拒绝修改持久基线'
                            }
                        } else {
                            def output = ReleaseStorage.aotPath(new File(env.RESERVED_SOURCE_ROOT),
                                env.AOT_BACKUP_OUTPUT_PATH, true)
                            env.FULLBUILD_AOT_FINGERPRINT = releaseFiles.directoryFingerprint(output)
                            if (!env.FULLBUILD_AOT_FINGERPRINT) error 'AOT 快照整体指纹为空'
                            echo "[ReleaseSource] Unity 构建成功，已接收框架写入的 AOT 快照：${output}"
                        }
                    }
                }
            }

            stage('SnapshotReleaseSource') {
                when {
                    expression {
                        return p('BUILD_MINIGAME') && !isHotUpdate
                    }
                }
                steps {
                    script {
                        def platform = p('PLATFORM')
                        def subDir = platform == 'TikTok' ? 'tt-minigame' : 'minigame'
                        def buildRoot = new File(projectBuildRoot).canonicalPath
                        def packageDir = new File(
                            "${buildRoot}\\${platform}\\${coreVersion}\\${subDir}").canonicalPath
                        def sourceRoot = env.RESERVED_SOURCE_ROOT
                        if (!sourceRoot || !fileExists(sourceRoot)) error 'Source 目录未预留'
                        def rawDir = "${sourceRoot}\\raw\\${subDir}"
                        def aotSnapshotDir = ReleaseStorage.aotPath(new File(sourceRoot),
                            env.AOT_BACKUP_OUTPUT_PATH, true)
                        def stateFile = "${sourceRoot}\\code-split-state.json"

                        if (!fileExists(packageDir)) {
                            error "[ReleaseSource] FullBuild 小游戏产物不存在: ${packageDir}"
                        }

                        def currentMd5 = releaseFiles.extractWasmMd5(packageDir)
                        if (!currentMd5) {
                            error "[ReleaseSource] 无法从 ${packageDir}\\wasmcode\\ 提取 WASM MD5"
                        }
                        def packageFingerprint = releaseFiles.directoryFingerprint(packageDir)
                        releaseFiles.mirrorDirectory(packageDir, rawDir, releaseSourceRoot)
                        if (releaseFiles.extractWasmMd5(rawDir) != currentMd5) {
                            error '[ReleaseSource] 原包快照 WASM MD5 校验失败'
                        }
                        def snapshotFingerprint = releaseFiles.directoryFingerprint(rawDir)
                        if (!packageFingerprint || snapshotFingerprint != packageFingerprint) {
                            error '[ReleaseSource] 原包快照完整指纹校验失败'
                        }
                        def aotFingerprint = env.FULLBUILD_AOT_FINGERPRINT
                        if (!aotFingerprint || releaseFiles.directoryFingerprint(aotSnapshotDir) != aotFingerprint) {
                            error '[ReleaseSource] 框架输出 AOT 快照在登记前发生变化'
                        }

                        def gitCommit = bat(script: '@git rev-parse HEAD', returnStdout: true).trim()
                        ReleaseStorage.writeAtomic(new File(stateFile), [
                            schemaVersion: 6,
                            buildTarget: 'MiniGame',
                            projectName: projectName,
                            state: 'SNAPSHOTTED',
                            platform: platform,
                            version: coreVersion,
                            sourceBuildNumber: env.BUILD_NUMBER as int,
                            sourceBuildUrl: env.BUILD_URL,
                            sourceGitCommit: gitCommit,
                            rawPackagePath: rawDir,
                            rawWasmMd5: currentMd5,
                            packageFingerprint: packageFingerprint,
                            aotBackupPath: aotSnapshotDir,
                            aotBackupFingerprint: aotFingerprint,
                            buildEnvironment: "${p('ENV')}",
                            debug: p('DEBUG') ? true : false,
                            createdAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Shanghai')),
                            updatedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Shanghai'))
                        ])
                        env.RELEASE_SOURCE_STATE_FILE = stateFile
                        echo "[ReleaseSource] 已保存不可变原包: ${platform} ${coreVersion} #${env.BUILD_NUMBER}"
                        echo "[ReleaseSource] WASM MD5=${currentMd5} | Package SHA256=${packageFingerprint}"
                        echo "[ReleaseSource] AOT SHA256=${aotFingerprint}"
                    }
                }
            }

            stage('Upload') {
                when {
                    expression {
                        return !isHotUpdate && p('BUILD_MINIGAME')
                    }
                }
                steps {
                    script {

                        def platform = p('PLATFORM')
                        def subDir = platform == 'TikTok' ? 'tt-minigame' : 'minigame'
                        def context = [farmRoot:buildFarmRoot,secretsRoot:projectSecretsRoot.path,
                            packageDir:"${projectBuildRoot}\\${platform}\\${coreVersion}\\${subDir}",
                            version:coreVersion,description:"v${coreVersion} #${env.BUILD_NUMBER} ${buildType}",
                            appId:localWechatAppId,uploadKey:localWechatUploadKeyPath,robot:uploadRobot,
                            projectName:projectName]
                        if (platform == 'WeChat') releaseWeChat.upload(context)
                        else releaseTikTok.upload(context)
                    }
                }
            }

            stage('MarkReleaseSourceReady') {
                when {
                    expression {
                        return !isHotUpdate && p('BUILD_MINIGAME')
                    }
                }
                steps {
                    script {
                        def stateFile = env.RELEASE_SOURCE_STATE_FILE ?: ''
                        if (!stateFile || !fileExists(stateFile)) {
                            error '[ReleaseSource] 上传完成但找不到原包状态文件'
                        }
                        releaseState.update(stateFile, [
                            state: 'SOURCE_READY',
                            releaseUploadedByBuild: env.BUILD_NUMBER as int,
                            releaseUploadBuildUrl: env.BUILD_URL
                        ])
                        echo '[ReleaseSource] 普通 release 已上传，原包可供独立 CodeSplit Job 后续处理'
                    }
                }
            }
        }

        post {
            cleanup {
                script { ReleaseStorage.release(projectLease) }
            }
            always {
                script {
                    if (projectLease) {
                        archiveArtifacts artifacts: 'build.log', allowEmptyArchive: true
                        releaseParameters.save(visibleParams(), savedParamsFile, p)
                        releaseParameters.sync(visibleParams(), savedParamsFile)
                    }
                }
            }
            success {
                script {
                    if (!projectLease || !feishuEnabled) {
                        echo '[Feishu] 通知已由项目配置关闭'
                    } else {
                        try {
                        def platform = p('PLATFORM') ?: config.platforms[0]
                        def buildEnvironment = "${p('ENV') ?: 'Unknown'}"
                        def isDebug = p('DEBUG') ? true : false
                        def planVersion = isHotUpdate ? (p('PLAN_VERSION') ?: coreVersion) : coreVersion
                        def miniGameUploaded = !isHotUpdate && p('BUILD_MINIGAME')
                        def title = miniGameUploaded ? '✅ 构建及上传成功' : '✅ 构建成功'
                        def extra = isHotUpdate
                            ? "\\n**结果**: 热更产物已生成并上传 CDN" +
                              "\\n**热更基线**: FullBuild #${env.HOTUPDATE_SOURCE_BUILD ?: '未知'}" +
                              "\\n**Source Commit**: ${env.HOTUPDATE_SOURCE_COMMIT ?: '未知'}" +
                              "\\n**配置来源**: 平台、环境、Debug 与 AOT 均继承自 Source"
                            : (miniGameUploaded
                                ? '\\n**上传结果**: 普通全量包已上传平台后台（未代码分包）\\n**CodeSplit 原包**: SOURCE_READY'
                                : '\\n**小游戏产物**: 本次未生成')
                        releaseCredentials.withFeishuWebhook(localFeishuWebhook) { webhook ->
                            releaseNotifications.sendFeishu(config.displayName, 'green', title, extra,
                                        coreVersion, planVersion, buildType, platform,
                                        buildEnvironment, isDebug, webhook)
                        }
                        } catch (e) {
                            echo "[Feishu] 成功通知发送失败: ${e.message}"
                        }
                    }
                }
            }
            failure {
                script {
                    if (!projectLease || !feishuEnabled) {
                        echo '[Feishu] 通知已由项目配置关闭'
                    } else {
                        try {
                        def platform = p('PLATFORM') ?: config.platforms[0]
                        def buildEnvironment = "${p('ENV') ?: 'Unknown'}"
                        def isDebug = p('DEBUG') ? true : false
                        def planVersion = isHotUpdate ? (p('PLAN_VERSION') ?: coreVersion) : coreVersion
                        def failureExtra = isHotUpdate
                            ? "\\n**请检查 build.log**\\n**热更基线**: FullBuild #${env.HOTUPDATE_SOURCE_BUILD ?: '未解析'}" +
                              "\\n**配置来源**: Source"
                            : '\\n**请检查 build.log**'
                        releaseCredentials.withFeishuWebhook(localFeishuWebhook) { webhook ->
                            releaseNotifications.sendFeishu(config.displayName, 'red', '❌ 构建失败', failureExtra,
                                        coreVersion, planVersion, buildType, platform,
                                        buildEnvironment, isDebug, webhook)
                        }
                        } catch (e) {
                            echo "[Feishu] 失败通知发送失败: ${e.message}"
                        }
                    }
                }
            }
        }
    }
}

// 内部版本比较

def _compareVersions(String a, String b) {
    def pa = a.tokenize('.').collect { it.toInteger() }
    def pb = b.tokenize('.').collect { it.toInteger() }
    def len = Math.max(pa.size(), pb.size())
    for (int i = 0; i < len; i++) {
        def va = i < pa.size() ? pa[i] : 0
        def vb = i < pb.size() ? pb[i] : 0
        if (va != vb) return va <=> vb
    }
    return 0
}
