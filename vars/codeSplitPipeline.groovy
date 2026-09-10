import org.july.release.ReleaseStorage

/**
 * 独立代码分包流水线。
 *
 * FullBuild 始终生成并上传普通 release，同时保存不可变原包。
 * 只有显式执行本流水线时才启动函数采集、生成二维码和完成正式分包。
 */
def call(Map config) {
    assert config.projectName : 'projectName is required'
    assert config.displayName : 'displayName is required'
    assert config.platforms   : 'platforms is required'

    def projectName = config.projectName.toString()
    def agentLabel = config.agentLabel ?: 'unity'

    def project = releaseProject.load(config)
    def projectLease = null
    def splitContext = [:]
    def buildFarmRoot = project.farm.path
    def projectRootFile = project.root
    def releaseSourceRoot = project.sources
    def codeSplitCacheRoot = project.splitCache
    def projectSecretsRoot = project.secrets
    def credentialsPath = project.credentialsFile
    def localCredentials = project.credentials
    def wechatConfig = localCredentials.wechat instanceof Map ? localCredentials.wechat : [:]
    def douyinConfig = localCredentials.douyin instanceof Map ? localCredentials.douyin : [:]
    def feishuConfig = localCredentials.feishu instanceof Map ? localCredentials.feishu : [:]
    def ttAppId = (douyinConfig.appId ?: '').toString()
    def localWechatAppId = (wechatConfig.appId ?: '').toString()
    def localWechatUploadKeyPath = (wechatConfig.uploadKeyPath ?: '').toString()
    def localWechatWasmSplitKeyPath = (wechatConfig.wasmSplitKeyPath ?: '').toString()
    def uploadRobot = releaseCredentials.optionalPositiveInteger(wechatConfig.uploadRobot, 'wechat.uploadRobot')
    def previewRobot = releaseCredentials.optionalPositiveInteger(wechatConfig.previewRobot, 'wechat.previewRobot')
    def feishuEnabled = feishuConfig.enabled as boolean
    def localFeishuWebhook = (feishuConfig.webhook ?: '').toString()
    def localFeishuAppId = (feishuConfig.appId ?: '').toString()
    def localFeishuAppSecret = (feishuConfig.appSecret ?: '').toString()

    def sourceChoiceScript = unityMiniGameSourceScript(
        kind: 'code-split', projectName: projectName,
        buildFarmRoot: buildFarmRoot, platforms: config.platforms)

    releaseParameters.sync(releaseParameters.definitions('code-split',
        [platforms:config.platforms,sourceScript:sourceChoiceScript]))

    pipeline {
        agent { label agentLabel }
        options { disableConcurrentBuilds() }

        stages {
            stage('PrepareBuildFarm') {
                steps {
                    script {
                        projectLease = ReleaseStorage.acquire(projectRootFile, env.BUILD_URL ?: env.BUILD_TAG)
                        [releaseSourceRoot, codeSplitCacheRoot,
                         projectSecretsRoot.canonicalPath].each { path ->
                            bat "@if not exist \"${path}\" mkdir \"${path}\""
                        }
                        echo "[BuildFarm] 项目配置路径: ${credentialsPath}"
                    }
                }
            }

            stage('ValidateCredentials') {
                steps {
                    script {
                        // 列表成员判断需要普通 String，不能保留插值生成的 GString。
                        def action = (params.ACTION ?: '').toString()
                        def platform = (params.PLATFORM ?: '').toString()
                        def knownActions = releaseParameters.actions()
                        if (!(action in knownActions)) {
                            error "ACTION 非法: ${action ?: '空'}"
                        }
                        if (!(platform in ['WeChat', 'TikTok']) || !(platform in config.platforms)) {
                            error "PLATFORM 非法或项目未启用: ${platform ?: '空'}"
                        }

                        def qrActions = ['启动代码分包采集', '再次启动代码分包采集', '刷新采集码']
                        if (platform == 'WeChat') {
                            releaseCredentials.requireProjectFields(['wechat.wasmSplitKeyPath': localWechatWasmSplitKeyPath],
                                credentialsPath.canonicalPath)
                            releaseCredentials.resolveProjectSecretFile(localWechatWasmSplitKeyPath,
                                projectSecretsRoot.canonicalPath, 'wechat.wasmSplitKeyPath')
                            if (action in qrActions) {
                                def required = [
                                    'wechat.appId': localWechatAppId,
                                    'wechat.uploadKeyPath': localWechatUploadKeyPath,
                                    'wechat.previewRobot': previewRobot,
                                ]
                                if (feishuEnabled) {
                                    required['feishu.webhook'] = localFeishuWebhook
                                    required['feishu.appId'] = localFeishuAppId
                                    required['feishu.appSecret'] = localFeishuAppSecret
                                }
                                releaseCredentials.requireProjectFields(required, credentialsPath.canonicalPath)
                                releaseCredentials.resolveProjectSecretFile(localWechatUploadKeyPath,
                                    projectSecretsRoot.canonicalPath, 'wechat.uploadKeyPath')
                            } else if (action == '生成正式分包并上传') {
                                def required = [
                                    'wechat.appId': localWechatAppId,
                                    'wechat.uploadKeyPath': localWechatUploadKeyPath,
                                    'wechat.uploadRobot': uploadRobot,
                                ]
                                if (feishuEnabled) {
                                    required['feishu.webhook'] = localFeishuWebhook
                                }
                                releaseCredentials.requireProjectFields(required, credentialsPath.canonicalPath)
                                releaseCredentials.resolveProjectSecretFile(localWechatUploadKeyPath,
                                    projectSecretsRoot.canonicalPath, 'wechat.uploadKeyPath')
                            }
                        } else {
                            releaseCredentials.requireProjectFields(['douyin.appId': ttAppId], credentialsPath.canonicalPath)
                            releaseJenkinsSecrets.check('douyin')
                            if (action != '查看采集状态') {
                                def required = [:]
                                if (feishuEnabled) {
                                    required['feishu.webhook'] = localFeishuWebhook
                                    if (action in qrActions) {
                                        required['feishu.appId'] = localFeishuAppId
                                        required['feishu.appSecret'] = localFeishuAppSecret
                                    }
                                }
                                releaseCredentials.requireProjectFields(required, credentialsPath.canonicalPath)
                            }
                        }
                    }
                }
            }

            stage('ValidateSource') {
                steps {
                    script {
                        def selectedSource = "${params.SOURCE ?: ''}".trim()
                        if (!selectedSource || selectedSource.startsWith('没有可用原包') ||
                            selectedSource.startsWith('SOURCE 读取失败')) {
                            error "请选择可用 SOURCE；当前值：${selectedSource ?: '空'}"
                        }
                        def parts = selectedSource.split('\\|', -1).collect { it.trim() }
                        if (parts.size() < 4) {
                            error "SOURCE 格式非法: ${selectedSource}"
                        }
                        def platform = parts[0]
                        def version = parts[1]
                        def sourceBuildNumber = parts[2]
                        def selectedState = parts[3]
                        if (!(platform in config.platforms) || !(version ==~ /\d+\.\d+\.\d+/) ||
                            !(sourceBuildNumber ==~ /[1-9]\d*/)) error 'SOURCE 标识非法'
                        if ("${params.PLATFORM ?: ''}" != platform) {
                            error "PLATFORM 与 SOURCE 不一致: PLATFORM=${params.PLATFORM}, SOURCE=${platform}"
                        }

                        def subDir = platform == 'TikTok' ? 'tt-minigame' : 'minigame'
                        def sourceRoot = "${releaseSourceRoot}\\${platform}\\${version}\\${sourceBuildNumber}"
                        def stateFile = "${sourceRoot}\\code-split-state.json"
                        if (!fileExists(stateFile)) {
                            error "原包状态文件不存在: ${stateFile}"
                        }

                        def state = readJSON file: stateFile, returnPojo: true
                        if (state.projectName != projectName ||
                            state.platform != platform || state.version != version ||
                            "${state.sourceBuildNumber}" != sourceBuildNumber || state.state != selectedState) {
                            error '[CodeSplit] 原包选项与状态文件不一致，请刷新参数页面后重试'
                        }
                        def requiredStatesByAction = [
                            '启动代码分包采集': ['SOURCE_READY'],
                            '再次启动代码分包采集': ['UPLOADED'],
                            '查看采集状态': ['COLLECTING', 'UPLOADED'],
                            '刷新采集码': ['COLLECTING'],
                            '生成正式分包并上传': ['COLLECTING']
                        ]
                        def requiredStates = requiredStatesByAction[params.ACTION]
                        if (!requiredStates) {
                            error "未知操作: ${params.ACTION}"
                        }
                        if (!(state.state in requiredStates)) {
                            error "当前操作“${params.ACTION}”要求状态 ${requiredStates.join('/')}，实际为 ${state.state}"
                        }

                        def rawDir = state.rawPackagePath as String
                        def expectedRawRoot = "${sourceRoot}\\raw\\".toLowerCase()
                        if (!rawDir.toLowerCase().startsWith(expectedRawRoot) || !fileExists(rawDir)) {
                            error "不可变原包不存在或路径非法: ${rawDir}"
                        }
                        def actualMd5 = releaseFiles.extractWasmMd5(rawDir)
                        if (!actualMd5 || actualMd5 != state.rawWasmMd5) {
                            error "原始 WASM 校验失败: state=${state.rawWasmMd5}, actual=${actualMd5}"
                        }
                        def actualFingerprint = releaseFiles.directoryFingerprint(rawDir)
                        if (!actualFingerprint || actualFingerprint != state.packageFingerprint) {
                            error '[CodeSplit] 原包完整指纹校验失败，拒绝继续'
                        }

                        env.CODE_SPLIT_PLATFORM = platform
                        env.CODE_SPLIT_VERSION = version
                        env.SOURCE_BUILD_NUMBER = sourceBuildNumber
                        env.SOURCE_ROOT = sourceRoot
                        splitContext = [farmRoot:buildFarmRoot,sources:releaseSourceRoot,secretsRoot:projectSecretsRoot.path,
                            raw:rawDir,collection:"${sourceRoot}\\collection\\${subDir}",
                            release:"${sourceRoot}\\release\\${subDir}",version:version,sourceBuild:sourceBuildNumber,
                            appId:localWechatAppId,uploadKey:localWechatUploadKeyPath,splitKey:localWechatWasmSplitKeyPath,
                            robot:uploadRobot,previewRobot:previewRobot,ttAppId:ttAppId,
                            projectName:projectName]
                        env.RAW_PACKAGE_DIR = rawDir
                        env.COLLECTION_PACKAGE_DIR = "${sourceRoot}\\collection\\${subDir}"
                        env.RELEASE_PACKAGE_DIR = "${sourceRoot}\\release\\${subDir}"
                        env.SPLIT_STATE_FILE = stateFile
                        if (!(state.buildEnvironment in ['Dev','Test','Prod']) || !(state.debug instanceof Boolean)) {
                            error 'Source 缺少合法环境或 Debug 标识'
                        }
                        env.SELECTED_SOURCE_STATE = state.state as String
                        env.COLLECTION_ROUND = "${state.collectionRound ?: 0}"
                        env.COLLECTION_CYCLE = "${state.collectionCycle ?: (state.collectionStartedByBuild ? 1 : 0)}"
                        env.PREVIOUS_UPLOAD_FUNC_SUMMARY =
                            params.ACTION == '再次启动代码分包采集' && state.metrics ?
                                releaseSplitMetrics.formatWasmMetrics(state.metrics as Map) : ''
                        def sourceEnvironment = "${state.buildEnvironment ?: '未知'}"
                        def sourceEnvironmentLabels = [
                            Dev: '开发（Dev）',
                            Test: '测试（Test）',
                            Prod: '生产（Prod）'
                        ]
                        env.SOURCE_BUILD_ENVIRONMENT_LABEL =
                            sourceEnvironmentLabels[sourceEnvironment] ?: sourceEnvironment
                        env.SOURCE_DEBUG_ENABLED_LABEL = state.debug ? '是' : '否'
                        echo "[Validate] ${platform} ${version} | FullBuild #${sourceBuildNumber} | " +
                             "environment=${env.SOURCE_BUILD_ENVIRONMENT_LABEL} | " +
                             "debug=${env.SOURCE_DEBUG_ENABLED_LABEL} | state=${state.state}"
                        echo "[Validate] rawWasmMd5=${actualMd5} | packageFingerprint=${actualFingerprint}"
                    }
                }
            }

            stage('StartCollection') {
                when { expression { return params.ACTION in ['启动代码分包采集', '再次启动代码分包采集'] } }
                steps {
                    script {
                        def restart = params.ACTION == '再次启动代码分包采集'
                        // 状态会重新交给 JsonOutput 持久化，必须使用普通 Map/null，不能混入 JSONNull。
                        def before = readJSON file:env.SPLIT_STATE_FILE, returnPojo: true
                        int cycle = restart ? ((before.collectionCycle ?: 1) as int) + 1 : 1
                        def context = splitContext + [restart:restart,
                            refRoot:"${codeSplitCacheRoot}\\${env.CODE_SPLIT_PLATFORM}"]
                        releaseState.once(env.SPLIT_STATE_FILE, "collect-${cycle}") {
                            if (env.CODE_SPLIT_PLATFORM == 'WeChat') releaseWeChat.prepare(context)
                            else releaseTikTok.prepare(context)
                            return [prepared:true]
                        }
                        releaseState.update(env.SPLIT_STATE_FILE, [
                            state:'COLLECTING',collectionPackagePath:env.COLLECTION_PACKAGE_DIR,
                            collectionCycle:cycle,collectionRound:1,
                            collectionStartedByBuild:env.BUILD_NUMBER as int,collectionBuildUrl:env.BUILD_URL,
                            lastUploadedMetrics:restart ? before.metrics : before.lastUploadedMetrics,
                            recollectionStartedByBuild:restart ? env.BUILD_NUMBER as int : before.recollectionStartedByBuild,
                            recollectionBuildUrl:restart ? env.BUILD_URL : before.recollectionBuildUrl
                        ])
                        env.COLLECTION_CYCLE = cycle.toString()
                        env.COLLECTION_ROUND = '1'
                        env.SELECTED_SOURCE_STATE = 'COLLECTING'
                    }
                }
            }

            stage('CollectionMetrics') {
                // 正式分包重试时，远端可能已由上一次 dosplit 生成新版本，
                // 此时旧 collection 目录上的 getinfo 会返回 waitDownloadWasmFile。
                // FinalSplit 本身会先执行 dosplit 再获取最新统计，因此跳过这个冗余的前置查询。
                when { expression { return params.ACTION != '生成正式分包并上传' } }
                steps {
                    script {
                        def queryDir = env.CODE_SPLIT_PLATFORM == 'TikTok' ?
                            env.RAW_PACKAGE_DIR :
                            (env.SELECTED_SOURCE_STATE == 'UPLOADED' ?
                                env.RELEASE_PACKAGE_DIR : env.COLLECTION_PACKAGE_DIR)
                        def oldState = readJSON file: env.SPLIT_STATE_FILE, returnPojo: true
                        def oldMetrics = oldState?.metrics
                        def oldIncrease = oldState?.metrics?.increase

                        _queryMetrics(splitContext, queryDir)
                        def info = releaseSplitMetrics.readWasmGameInfo(env.CODE_SPLIT_PLATFORM, queryDir)
                        if (!info) { error '[CollectionMetrics] 本次命令未生成函数统计，拒绝使用旧文件' }
                        def isStatusRefresh = params.ACTION in ['查看采集状态', '刷新采集码']
                        def sameMetricType = oldMetrics && oldMetrics.isProfile == info.isProfile
                        if (isStatusRefresh && oldState?.state == 'COLLECTING' &&
                            sameMetricType && oldIncrease != null) {
                            info.increaseDelta = info.increase - (oldIncrease as long)
                        }

                        int round = env.COLLECTION_ROUND as int
                        if (params.ACTION in ['查看采集状态', '刷新采集码']) { round++ }
                        echo releaseSplitMetrics.formatWasmInfoBlock(
                            "当前采集状态（采集周期 ${env.COLLECTION_CYCLE ?: '1'}）", info, round)
                        env.WASM_FUNC_SUMMARY = releaseSplitMetrics.formatWasmSummary(info, round)
                        env.COLLECTION_ROUND = "${round}"
                        releaseState.update(env.SPLIT_STATE_FILE, [metrics: info, collectionRound: round])
                    }
                }
            }

            stage('FinalSplit') {
                when { expression { return params.ACTION == '生成正式分包并上传' } }
                steps {
                    script {
                        def completed = releaseState.finalizePackage(env.SPLIT_STATE_FILE, "finalize-${env.COLLECTION_CYCLE}") {
                            def ttVersion
                            if (env.CODE_SPLIT_PLATFORM == 'WeChat') releaseWeChat.finalizePackage(splitContext)
                            else ttVersion = releaseTikTok.finalizePackage(splitContext)
                            releaseSplitMetrics.validateReleasePackage(env.CODE_SPLIT_PLATFORM, env.RELEASE_PACKAGE_DIR)
                            def queryDir = env.CODE_SPLIT_PLATFORM == 'TikTok' ? env.RAW_PACKAGE_DIR : env.RELEASE_PACKAGE_DIR
                            _queryMetrics(splitContext, queryDir)
                            def info = releaseSplitMetrics.readWasmGameInfo(env.CODE_SPLIT_PLATFORM, queryDir)
                            if (!info) error '[FinalSplit] 没有本次函数统计'
                            return [metrics:info,ttVersion:ttVersion,build:env.BUILD_NUMBER,
                                    fingerprint:releaseFiles.directoryFingerprint(env.RELEASE_PACKAGE_DIR)]
                        }
                        if (releaseFiles.directoryFingerprint(env.RELEASE_PACKAGE_DIR) != completed.fingerprint) {
                            error '[FinalSplit] 已完成分包的产物被改变，拒绝重用或重复上传'
                        }
                        env.TIKTOK_RELEASE_SPLIT_VERSION = completed.ttVersion ?: ''
                        env.WASM_FUNC_SUMMARY = releaseSplitMetrics.formatWasmSummary(completed.metrics, env.COLLECTION_ROUND as int)
                        echo releaseSplitMetrics.formatWasmInfoBlock('正式分包候选产物',completed.metrics,env.COLLECTION_ROUND as int)
                        def changes = [metrics:completed.metrics,releasePackagePath:env.RELEASE_PACKAGE_DIR,
                            lastFinalizedByBuild:completed.build.toString().toInteger(),releaseFingerprint:completed.fingerprint]
                        if (completed.ttVersion) changes.releaseSplitVersion = completed.ttVersion.toString().toInteger()
                        releaseState.update(env.SPLIT_STATE_FILE,changes)
                    }
                }
            }

            stage('UploadRelease') {
                when { expression { return params.ACTION == '生成正式分包并上传' } }
                steps {
                    script {
                        def context = splitContext + [packageDir:env.RELEASE_PACKAGE_DIR,
                            description:"v${env.CODE_SPLIT_VERSION} FullBuild#${env.SOURCE_BUILD_NUMBER} CodeSplit"]
                        def receipt = releaseState.once(env.SPLIT_STATE_FILE, "upload-${env.COLLECTION_CYCLE}") {
                            if (env.CODE_SPLIT_PLATFORM == 'WeChat') releaseWeChat.upload(context)
                            else releaseTikTok.upload(context)
                            return [build:env.BUILD_NUMBER, url:env.BUILD_URL]
                        }
                        // 上传回执先落盘。参考版本写入失败后可重试，不再重复 dosplit/upload。
                        def refRoot = "${codeSplitCacheRoot}\\${env.CODE_SPLIT_PLATFORM}"
                        if (env.CODE_SPLIT_PLATFORM == 'TikTok') {
                            def state = readJSON file:env.SPLIT_STATE_FILE, returnPojo: true
                            releaseSplitMetrics.saveTikTokRefVersion(state.releaseSplitVersion.toString(),refRoot)
                        } else {
                            releaseSplitMetrics.saveWeChatRefMd5(env.RAW_PACKAGE_DIR,refRoot)
                        }
                        releaseState.update(env.SPLIT_STATE_FILE,[state:'UPLOADED',
                            uploadedByBuild:receipt.build.toString().toInteger(),uploadBuildUrl:receipt.url])
                    }
                }
            }

            stage('RefreshCollectionQR') {
                when { expression { return params.ACTION in ['启动代码分包采集', '再次启动代码分包采集', '刷新采集码'] } }
                steps {
                    script {
                        def qrcodePath = "${WORKSPACE}\\preview_qr.jpg"
                        bat '@if exist preview_qr.jpg del /f /q preview_qr.jpg'
                        def context = splitContext + [qrcode:qrcodePath]
                        if (env.CODE_SPLIT_PLATFORM == 'WeChat') releaseWeChat.preview(context)
                        else releaseTikTok.preview(context)
                        if (!fileExists('preview_qr.jpg')) { error '[RefreshCollectionQR] 本次没有生成二维码' }
                        if (feishuEnabled) {
                            env.PREVIEW_IMAGE_KEY = releaseNotifications.uploadFeishuImage(
                                qrcodePath, localFeishuAppId, localFeishuAppSecret)
                            if (!env.PREVIEW_IMAGE_KEY) {
                                error '[RefreshCollectionQR] 二维码上传飞书失败'
                            }
                        } else {
                            env.PREVIEW_IMAGE_KEY = ''
                            archiveArtifacts artifacts: 'preview_qr.jpg', fingerprint: false
                            echo '[Feishu] 通知已关闭；采集二维码已保存为 Jenkins 构件 preview_qr.jpg'
                        }
                    }
                }
            }
        }

        post {
            cleanup {
                script { ReleaseStorage.release(projectLease) }
            }
            success {
                script {
                    if (feishuEnabled && params.ACTION in ['启动代码分包采集', '再次启动代码分包采集', '刷新采集码']) {
                        try {
                            def previousUpload = params.ACTION == '再次启动代码分包采集' ?
                                "\\n**上次正式包函数**: ${env.PREVIOUS_UPLOAD_FUNC_SUMMARY ?: '统计暂不可用'}" : ''
                            def extra = "\\n**状态**: COLLECTING（仅供函数采集）" +
                                        "\\n**采集周期**: 第 ${env.COLLECTION_CYCLE ?: '1'} 轮" +
                                        "\\n**原始构建**: FullBuild #${env.SOURCE_BUILD_NUMBER}" +
                                        "\\n**原包环境**: ${env.SOURCE_BUILD_ENVIRONMENT_LABEL ?: '未知'}" +
                                        "\\n**原包是否开启 Debug**: ${env.SOURCE_DEBUG_ENABLED_LABEL ?: '未知'}" +
                                        "${previousUpload}" +
                                        "\\n**本轮函数**: ${env.WASM_FUNC_SUMMARY ?: '统计暂不可用'}"
                            def title = params.ACTION == '启动代码分包采集' ?
                                '✅ 代码分包采集已启动' :
                                (params.ACTION == '再次启动代码分包采集' ?
                                    '✅ 新一轮代码分包采集已启动' :
                                    '✅ 代码分包采集码已刷新')
                            releaseCredentials.withFeishuWebhook(localFeishuWebhook) { webhook ->
                                releaseNotifications.sendFeishuResplit(config.displayName, 'green', title,
                                    env.CODE_SPLIT_VERSION, env.CODE_SPLIT_PLATFORM, webhook,
                                    env.PREVIEW_IMAGE_KEY ?: '', extra)
                            }
                        } catch (e) {
                            echo "[Feishu] 采集码通知发送失败: ${e.message}"
                        }
                    } else if (feishuEnabled && params.ACTION == '生成正式分包并上传') {
                        try {
                            def extra = "\\n**状态**: UPLOADED（正式分包已上传平台后台）" +
                                        "\\n**原始构建**: FullBuild #${env.SOURCE_BUILD_NUMBER}" +
                                        "\\n**原包环境**: ${env.SOURCE_BUILD_ENVIRONMENT_LABEL ?: '未知'}" +
                                        "\\n**原包是否开启 Debug**: ${env.SOURCE_DEBUG_ENABLED_LABEL ?: '未知'}" +
                                        "\\n**上传机器人**: ${uploadRobot}" +
                                        "\\n**函数**: ${env.WASM_FUNC_SUMMARY ?: '统计暂不可用'}" +
                                        "\\n**后续操作**: 请从平台后台进入该版本进行真机验证"
                            releaseCredentials.withFeishuWebhook(localFeishuWebhook) { webhook ->
                                releaseNotifications.sendFeishuResplit(config.displayName, 'green',
                                    '✅ 正式分包已生成并上传',
                                    env.CODE_SPLIT_VERSION, env.CODE_SPLIT_PLATFORM, webhook, '', extra)
                            }
                        } catch (e) {
                            echo "[Feishu] 正式分包成功通知发送失败: ${e.message}"
                        }
                    } else if (!feishuEnabled && params.ACTION != '查看采集状态') {
                        echo '[Feishu] 通知已由项目配置关闭'
                    }
                }
            }
            failure {
                script {
                    if (feishuEnabled && params.ACTION != '查看采集状态') {
                        try {
                            releaseCredentials.withFeishuWebhook(localFeishuWebhook) { webhook ->
                                releaseNotifications.sendFeishuResplit(config.displayName, 'red', '❌ 代码分包操作失败',
                                    env.CODE_SPLIT_VERSION ?: '未知', env.CODE_SPLIT_PLATFORM ?: '未知', webhook, '', '')
                            }
                        } catch (e) {
                            echo "[Feishu] 失败通知发送失败: ${e.message}"
                        }
                    } else if (!feishuEnabled && params.ACTION != '查看采集状态') {
                      echo '[Feishu] 通知已由项目配置关闭'
                    }
                }
            }
        }
    }
}
def _queryMetrics(Map context, String queryDir) {
    def query = {
        releaseSplitMetrics.runWasmGetinfo(env.CODE_SPLIT_PLATFORM,queryDir,context.ttAppId,
            context.splitKey,context.secretsRoot)
    }
    if (env.CODE_SPLIT_PLATFORM == 'TikTok') releaseTikTok.withSession(context,query)
    else query()
}
