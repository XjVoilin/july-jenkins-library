import org.july.release.ReleaseStorage
/** 抖音工具登录态按机器共享；跨项目也必须串行执行登录与对应命令。 */
def withSession(Map c, Closure work) {
    def lease = ReleaseStorage.acquire(new File(c.farmRoot,'ToolSessions/TikTok'), env.BUILD_URL ?: env.BUILD_TAG)
    try {
        releaseJenkinsSecrets.withPair('douyin','DY_EMAIL','DY_PASSWORD') {
            releaseFiles.runDiagnosedBat('抖音登录','tmg login-e "%DY_EMAIL%" "%DY_PASSWORD%"',
                [env.DY_EMAIL, env.DY_PASSWORD])
            work()
        }
    } finally { ReleaseStorage.release(lease) }
}
def upload(Map c) {
    withSession(c) {
        releaseFiles.runCheckedBat('抖音正式包上传',
            'tmg upload "'+c.packageDir+'" -v "'+c.version+'" -c "'+c.description+'"',['Upload success'])
    }
}
def preview(Map c) {
    withSession(c) {
        releaseFiles.runCheckedBat('抖音采集码刷新',
            'tmg preview "'+c.collection+'" --disable-cache -o "'+c.qrcode+'"')
    }
}
def finalizePackage(Map c) {
    releaseFiles.mirrorManagedDirectory(c.raw,c.release,'release',c.sources)
    withSession(c) {
        def output = releaseFiles.runCheckedBat('抖音正式分包',
            'tt-wasmsplit-ci dosplit -p "'+c.release+'" -i '+c.ttAppId)
        releaseSplitMetrics.extractTikTokSplitVersion(output)
    }
}

def prepare(Map c) {
    // 抖音 prepare 包始终由不可变原包生成；再次采集时引用最近正式分包版本。
    releaseFiles.mirrorManagedDirectory(c.raw, c.collection, 'collection', c.sources)
    def refVersion = releaseSplitMetrics.readReference("${c.refRoot}\\last_split_version")
    echo "[CodeSplit] 抖音增量分包参考版本: ${refVersion ?: '未使用'}"
    withSession(c) {
        def initCmd = "tt-wasmsplit-ci init -p \"${c.collection}\" -i ${c.ttAppId} -m \"v${c.version}#${c.sourceBuild}\""
        if (refVersion) { initCmd += " -r ${refVersion}" }
        releaseFiles.runCheckedBat('抖音 prepare 包生成', initCmd)
    }

}
