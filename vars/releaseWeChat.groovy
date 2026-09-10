/** 微信命令适配；公共流程不拼接平台命令。 */
def upload(Map c) {
    releaseCredentials.withWeChatUploadCredentials(c.appId,c.uploadKey,c.secretsRoot) {
        releaseFiles.runCheckedBat('微信正式包上传',
            'miniprogram-ci upload --pp "'+c.packageDir+'" --pkp "%WX_KEY_PATH%" --appid "%WX_APPID%" --uv "'+
            c.version+'" -r '+c.robot+' --desc "'+c.description+'"')
    }
}
def preview(Map c) {
    releaseCredentials.withWeChatUploadCredentials(c.appId,c.uploadKey,c.secretsRoot) {
        releaseFiles.runCheckedBat('微信采集码刷新',
            'miniprogram-ci preview --pp "'+c.collection+'" --pkp "%WX_KEY_PATH%" --appid "%WX_APPID%" --uv "'+
            c.version+'" -r '+c.previewRobot+' --qrcode-format image --qrcode-output-dest "'+c.qrcode+'"')
    }
}
def finalizePackage(Map c) {
    releaseFiles.mirrorManagedDirectory(c.collection,c.release,'release',c.sources)
    releaseCredentials.withFileCredential(c.splitKey,c.secretsRoot,'wechat.wasmSplitKeyPath','SPLIT_KEY') {
        releaseFiles.runCheckedBat('微信正式分包',
            'wasmsplit-ci dosplit -p "'+c.release+'" -k "%SPLIT_KEY%" --release')
    }
    null
}

def prepare(Map c) {
    if (c.restart) {
        // 微信同一份 WASM 的迭代采集应从最近一次正式分包继续，
        // 保留最新 subVersion 上下文并生成下一个 Profile 子版本。
        releaseSplitMetrics.validateReleasePackage('WeChat', c.release)
        releaseFiles.mirrorManagedDirectory(c.release, c.collection, 'collection', c.sources)
        releaseCredentials.withFileCredential(c.splitKey, c.secretsRoot,
            'wechat.wasmSplitKeyPath', 'SPLIT_KEY') {
            releaseFiles.runCheckedBat('微信新一轮 profile 包生成',
                "wasmsplit-ci dosplit -p \"${c.collection}\" -k \"%SPLIT_KEY%\"")
        }
    } else {
        releaseFiles.mirrorManagedDirectory(c.raw, c.collection, 'collection', c.sources)
        def previousMd5 = releaseSplitMetrics.readReference("${c.refRoot}\\last_split_md5")
        releaseCredentials.withFileCredential(c.splitKey, c.secretsRoot,
            'wechat.wasmSplitKeyPath', 'SPLIT_KEY') {
            def initCmd = "wasmsplit-ci init -p \"${c.collection}\" -k \"%SPLIT_KEY%\" -d \"v${c.version}#${c.sourceBuild}\""
            if (previousMd5) { initCmd += " -r ${previousMd5}" }
            releaseFiles.runCheckedBat('微信分包采集初始化', initCmd)
            if (releaseSplitMetrics.isCollectionPackageReady(c.collection)) {
                echo '[StartCollection] init 已生成有效微信 Profile 包，跳过重复 dosplit'
            } else {
                echo '[StartCollection] init 未生成完整微信 Profile 包，补执行一次 dosplit'
                releaseFiles.runCheckedBat('微信 profile 包补充生成',
                    "wasmsplit-ci dosplit -p \"${c.collection}\" -k \"%SPLIT_KEY%\"")
            }
        }
    }
    releaseSplitMetrics.validateCollectionPackage('WeChat', c.collection)

}
