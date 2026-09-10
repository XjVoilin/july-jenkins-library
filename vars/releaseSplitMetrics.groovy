/** 共享构建模块：releaseSplitMetrics。仅由流水线调用。 */
def validateCollectionPackage(String platform, String packageDir) {
    if (platform != 'WeChat') { return }
    def configPath = "${packageDir}\\.plugincache\\codesplit\\codesplit-config.json"
    if (!fileExists(configPath)) {
        error "[CollectionValidation] 缺少微信 Profile 分包配置: ${configPath}"
    }
    def splitConfig = readJSON file: configPath, returnPojo: true
    if (splitConfig.isProfile != true || splitConfig.hasUsedWasmCodeSplit != true) {
        error '[CollectionValidation] 微信 Profile 分包未完成，禁止生成采集码'
    }
    echo '[CollectionValidation] WeChat Profile 分包结构校验通过'
}

def isCollectionPackageReady(String packageDir) {
    def configPath = "${packageDir}\\.plugincache\\codesplit\\codesplit-config.json"
    if (!fileExists(configPath)) { return false }
    try {
        def splitConfig = readJSON file: configPath, returnPojo: true
        return splitConfig.isProfile == true && splitConfig.hasUsedWasmCodeSplit == true
    } catch (e) {
        echo "[CollectionValidation] init 产物暂不可用，将尝试 dosplit: ${e.message}"
        return false
    }
}

def validateReleasePackage(String platform, String packageDir) {
    if (platform == 'WeChat') {
        def configPath = "${packageDir}\\.plugincache\\codesplit\\codesplit-config.json"
        if (!fileExists(configPath)) { error "[ReleaseValidation] 缺少微信分包配置: ${configPath}" }
        def splitConfig = readJSON file: configPath, returnPojo: true
        // wasmsplit-ci 1.1.33 的正式分包配置不再生成 isFinalStep；
        // 当前版本以 isProfile=false 和 hasUsedWasmCodeSplit=true 判定正式分包完成。
        if (splitConfig.isProfile != false || splitConfig.hasUsedWasmCodeSplit != true) {
            error '[ReleaseValidation] 微信产物不是已完成的正式分包，禁止上传'
        }
    } else {
        def required = ['wasmcode-android', 'wasmcode1-android', 'wasmcode-ios']
        def missing = required.findAll { !fileExists("${packageDir}\\${it}") }
        if (missing) { error "[ReleaseValidation] 抖音正式分包缺少目录: ${missing.join(', ')}" }
    }
    echo "[ReleaseValidation] ${platform} 正式分包结构校验通过"
}

def wasmGameInfoPath(String platform, String packageDir) {
    if (platform == 'TikTok') {
        def parentDir = packageDir.substring(0, packageDir.lastIndexOf('\\'))
        return "${parentDir}\\gameinfo.txt"
    }
    return "${packageDir}\\.plugincache\\codesplit\\gameinfo.txt"
}

def runWasmGetinfo(String platform, String packageDir, String ttAppId,
                     String localWechatWasmSplitKeyPath, String projectSecretsRoot) {
    def infoPath = wasmGameInfoPath(platform, packageDir)
    bat "@if exist \"${infoPath}\" del /f /q \"${infoPath}\""
    if (platform == 'WeChat') {
        releaseCredentials.withFileCredential(localWechatWasmSplitKeyPath, projectSecretsRoot,
                                    'wechat.wasmSplitKeyPath', 'SPLIT_KEY') {
            releaseFiles.runCheckedBat('微信函数信息查询',
                "wasmsplit-ci getinfo -p \"${packageDir}\" -k \"%SPLIT_KEY%\"")
        }
    } else {
        releaseFiles.runCheckedBat('抖音函数信息查询',
            "tt-wasmsplit-ci getinfo -p \"${packageDir}\" -i ${ttAppId}")
    }
    if (!fileExists(infoPath)) {
        error "[CollectionMetrics] 本次 getinfo 未生成 ${infoPath}，拒绝复用旧统计文件"
    }
}

def readWasmGameInfo(String platform, String packageDir) {
    def path = wasmGameInfoPath(platform, packageDir)
    if (!fileExists(path)) { return null }
    def json = readJSON file: path, returnPojo: true
    def total, current, increase, collected, isProfile
    if (platform == 'TikTok' && json.info) {
        total = (json.info.total_wasm_func_count ?: 0) as long
        current = (json.info.main_wasm_func_count ?: 0) as long
        increase = (json.info.added_func_count ?: 0) as long
        collected = (json.info.collected_func_count ?: 0) as long
        isProfile = null
    } else {
        total = (json.sourceFuncNum ?: 0) as long
        current = (json.currentNum ?: 0) as long
        increase = (json.increaseNum ?: 0) as long
        collected = null
        isProfile = json.isProfile != null ? json.isProfile : true
    }
    def ratio = total > 0 ? String.format('%.1f', current * 100.0 / total) : '0.0'
    // 微信 CLI 的 currentNum 是扣除 increaseNum 后的基线函数数；实际（或预计）
    // 首包函数数应为 currentNum + increaseNum。抖音字段保持平台原始口径。
    def resultCurrent = isProfile != null ?
        Math.min(total, Math.max(0L, current + increase)) : current
    def resultRatio = total > 0 ?
        String.format('%.1f', resultCurrent * 100.0 / total) : '0.0'
    return [total: total, current: current, increase: increase, collected: collected,
            deferred: Math.max(0L, total - current), ratio: ratio,
            resultCurrent: resultCurrent, resultRatio: resultRatio,
            resultDeferred: Math.max(0L, total - resultCurrent), isProfile: isProfile]
}

def formatWasmSummary(Map info, int round) {
    def metrics = wasmMetricView(info)
    def trend = info.increaseDelta != null ?
        "｜距上次查询新增${info.increaseDelta >= 0 ? '+' : ''}${info.increaseDelta}" : ''
    if (metrics.isWeChat) {
        if (metrics.isProfile) {
            return "轮次${round}｜总数${metrics.total}｜基线首包${metrics.current}(${metrics.ratio}%)｜" +
                   "本轮已采集${metrics.increase}｜预计正式首包${metrics.resultCurrent}(${metrics.resultRatio}%)｜" +
                   "预计延后${metrics.resultDeferred}${trend}"
        }
        return "轮次${round}｜总数${metrics.total}｜基线首包${metrics.current}(${metrics.ratio}%)｜" +
               "本次纳入新增${metrics.increase}｜正式首包${metrics.resultCurrent}(${metrics.resultRatio}%)｜" +
               "延后${metrics.resultDeferred}"
    }
    def collected = info.collected != null ? info.collected : '平台未单列'
    return "轮次${round}｜总数${metrics.total}｜首包${metrics.current}(${metrics.ratio}%)｜" +
           "本轮新增${metrics.increase}｜累计采集${collected}｜延后${metrics.deferred}${trend}"
}

def formatWasmMetrics(Map info) {
    def metrics = wasmMetricView(info)
    if (metrics.isWeChat) {
        if (metrics.isProfile) {
            return "总数${metrics.total}｜基线首包${metrics.current}(${metrics.ratio}%)｜" +
                   "本轮已采集${metrics.increase}｜预计正式首包${metrics.resultCurrent}(${metrics.resultRatio}%)｜" +
                   "预计延后${metrics.resultDeferred}"
        }
        return "总数${metrics.total}｜基线首包${metrics.current}(${metrics.ratio}%)｜" +
               "本次纳入新增${metrics.increase}｜正式首包${metrics.resultCurrent}(${metrics.resultRatio}%)｜" +
               "延后${metrics.resultDeferred}"
    }
    return "总数${metrics.total}｜首包${metrics.current}(${metrics.ratio}%)｜" +
           "新增${metrics.increase}｜延后${metrics.deferred}"
}

def formatWasmInfoBlock(String title, Map info, int round) {
    def metrics = wasmMetricView(info)
    def trend = info.increaseDelta != null ?
        "\n  距上次查询新增:   ${info.increaseDelta >= 0 ? '+' : ''}${info.increaseDelta}" : ''
    def metricsBody
    if (metrics.isWeChat && metrics.isProfile) {
        metricsBody = """  基线首包函数数:   ${metrics.current} (${metrics.ratio}%)
  本轮已采集函数:   ${metrics.increase}
  预计正式首包:     ${metrics.resultCurrent} (${metrics.resultRatio}%)
  预计延后加载:     ${metrics.resultDeferred}"""
    } else if (metrics.isWeChat) {
        metricsBody = """  基线首包函数数:   ${metrics.current} (${metrics.ratio}%)
  本次纳入新增:     ${metrics.increase}
  正式首包函数数:   ${metrics.resultCurrent} (${metrics.resultRatio}%)
  延后加载函数数:   ${metrics.resultDeferred}"""
    } else {
        def collected = info.collected != null ? info.collected : '平台未单独提供'
        metricsBody = """  当前首包函数数:   ${metrics.current} (${metrics.ratio}%)
  本轮新增函数数:   ${metrics.increase}
  平台累计采集数:   ${collected}
  延后加载函数数:   ${metrics.deferred}"""
    }
    return """
════════════════════════════════════════
  ${title}
════════════════════════════════════════
  采集轮次:         ${round}
  原始函数总数:     ${metrics.total}
${metricsBody}${trend}
════════════════════════════════════════
  说明: 指标用于判断关键路径覆盖情况，不设置跨项目通用的强制比例。
        是否继续采集应结合新增趋势、关键业务覆盖和真机验证决定。
════════════════════════════════════════"""
}

def wasmMetricView(Map info) {
    def total = (info.total ?: 0) as long
    def current = (info.current ?: 0) as long
    def increase = (info.increase ?: 0) as long
    def ratio = info.ratio != null ? info.ratio : (total > 0 ?
        String.format('%.1f', current * 100.0 / total) : '0.0')
    def isWeChat = info.isProfile != null
    def resultCurrent = info.resultCurrent != null ? (info.resultCurrent as long) :
        (isWeChat ? Math.min(total, Math.max(0L, current + increase)) : current)
    def resultRatio = info.resultRatio != null ? info.resultRatio : (total > 0 ?
        String.format('%.1f', resultCurrent * 100.0 / total) : '0.0')
    def deferred = info.deferred != null ? (info.deferred as long) : Math.max(0L, total - current)
    def resultDeferred = info.resultDeferred != null ? (info.resultDeferred as long) :
        Math.max(0L, total - resultCurrent)
    return [total: total, current: current, increase: increase, ratio: ratio,
            deferred: deferred, resultCurrent: resultCurrent, resultRatio: resultRatio,
            resultDeferred: resultDeferred, isWeChat: isWeChat, isProfile: info.isProfile == true]
}

def extractTikTokSplitVersion(String output) {
    def matcher = (output =~ /(?im)current split version:\s*(\d+)/)
    if (!matcher.find()) {
        error '[CodeSplit] 抖音 dosplit 未返回 current split version，禁止上传'
    }
    def version = matcher.group(1) as int
    if (version <= 0) {
        error "[CodeSplit] 抖音 dosplit 返回非法版本: ${version}"
    }
    return "${version}"
}

def saveTikTokRefVersion(String splitVersion, String refRoot) {
    if (!(splitVersion ==~ /[1-9]\d*/)) {
        error "[CodeSplit] 抖音正式分包参考版本非法: ${splitVersion ?: '空'}"
    }
    bat "@if not exist \"${refRoot}\" mkdir \"${refRoot}\""
    def target = "${refRoot}\\last_split_version"
    writeFile file: target, text: splitVersion
    echo "[CodeSplit] 已记录抖音正式分包参考版本: ${splitVersion}"
}

def saveWeChatRefMd5(String rawDir, String refRoot) {
    def rawMd5 = releaseFiles.extractWasmMd5(rawDir)
    if (!rawMd5) { error '[CodeSplit] 无法记录微信正式分包原始 MD5' }
    bat "@if not exist \"${refRoot}\" mkdir \"${refRoot}\""
    def target = "${refRoot}\\last_split_md5"
    writeFile file: target, text: rawMd5
    echo "[CodeSplit] 已记录微信正式分包参考 MD5: ${rawMd5}"
}

def readReference(String path) {
    return fileExists(path) ? readFile(file: path).trim() : ''
}
