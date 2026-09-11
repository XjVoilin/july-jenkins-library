import com.cloudbees.groovy.cps.NonCPS
import org.july.release.ReleaseStorage

/** 发布标签由 Jenkins 负责。补齐发布只读取回执和快照，不重新构建或上传。调用方持有项目锁及 Git 凭证。 */
def plan(Map request) {
    def platform = request.platform.toString().toLowerCase()
    def environment = request.environment.toString().toLowerCase()
    if (!(platform in ['wechat','tiktok']) || !(environment in ['dev','test','prod']) ||
        !(request.coreVersion ==~ /\d+\.\d+\.\d+/) || !(request.planVersion ==~ /\d+\.\d+\.\d+/) ||
        !(request.buildNumber ==~ /[1-9]\d*/) || !(request.buildType in ['FullBuild','HotUpdateBuild']))
        error '发布标签参数非法'
    def commit = bat(script:'@git rev-parse HEAD', returnStdout:true).trim()
    def main = "release/${environment}/${platform}/${request.coreVersion}/${request.planVersion}"
    def exists = bat(script:"@git tag -l \"${main}\"", returnStdout:true).trim()
    def kind = request.buildType == 'HotUpdateBuild' ? 'hot' : 'full'
    def name = exists ? "${main}+${kind}-b${request.buildNumber}" : main
    [name:name.toString(), commit:commit,
     message:"BuildType: ${request.buildType}\nPlatform: ${request.platform}\nEnvironment: ${request.environment}\n" +
         "CoreVersion: ${request.coreVersion}\nPlanVersion: ${request.planVersion}\n" +
         "BuildNumber: ${request.buildNumber}\nJenkins: ${request.buildUrl}\nCommit: ${commit}\nCDN: ${request.cdnUrl}\n"]
}

def publish(Map tag) {
    if (!(tag.name ==~ /release\/(dev|test|prod)\/(wechat|tiktok)\/\d+\.\d+\.\d+\/\d+\.\d+\.\d+(\+(full|hot)-b[1-9]\d*)?/) ||
        !(tag.commit ==~ /[0-9a-fA-F]{40}/) || !tag.message)
        error '发布标签记录非法'
    def name = tag.name
    // Remote read also handles a successful push whose client response was lost.
    def remote = bat(script:"@git ls-remote origin \"refs/tags/${name}\" \"refs/tags/${name}^{}\"", returnStdout:true).trim()
    if (remote) {
        def commit = remoteCommit(remote, name.toString())
        if (!commit || !commit.equalsIgnoreCase(tag.commit)) error '远端标签指向另一提交，禁止覆盖'
        echo "[ReleaseTag] 已确认远端标签: ${name}"
        return
    }
    def local = bat(script:"@git tag -l \"${name}\"", returnStdout:true).trim()
    if (local) {
        def commit = bat(script:"@git rev-parse \"${name}^{commit}\"", returnStdout:true).trim()
        if (!commit.equalsIgnoreCase(tag.commit)) error '本地标签指向另一提交，禁止覆盖'
    } else {
        writeFile file:'release-tag-message.txt', text:tag.message, encoding:'UTF-8'
        bat "@git tag -a \"${name}\" ${tag.commit} -F release-tag-message.txt"
    }
    bat "@git push origin \"refs/tags/${name}\""
}

// Pure text parsing has no Pipeline steps. Keep collection closures out of CPS continuation state.
@NonCPS
private static String remoteCommit(String output, String name) {
    def lines = output.readLines().collect { it.split(/\s+/) }
    def resolved = lines.find { it[1] == "refs/tags/${name}^{}" } ?: lines.find { it[1] == "refs/tags/${name}" }
    resolved == null ? null : resolved[0]
}

def completeFull(String stateFile) {
    def state = ReleaseStorage.read(new File(stateFile))
    if (!(state.state in ['SNAPSHOTTED','SOURCE_READY']) || !state.releaseTag ||
        state.releaseTag.commit != state.sourceGitCommit)
        error 'Source 不具备完整发布记录'
    def upload = state.operations?.get('full-upload')
    if (upload?.status != 'DONE') error '平台上传未确认成功，禁止补齐发布；先核对平台结果'
    def sourceRoot = new File(stateFile).parentFile
    def aot = ReleaseStorage.aotPath(sourceRoot, state.aotBackupPath.toString(), true)
    def raw = ReleaseStorage.inside(sourceRoot, new File(state.rawPackagePath.toString()))
    if (releaseFiles.directoryFingerprint(aot) != state.aotBackupFingerprint ||
        releaseFiles.directoryFingerprint(raw.path) != state.packageFingerprint)
        error 'Source 快照发生变化，禁止补齐发布'
    publish(state.releaseTag as Map)
    releaseState.update(stateFile, [state:'SOURCE_READY',
        releaseUploadedByBuild:upload.result.build, releaseUploadBuildUrl:upload.result.url,
        releaseTagName:state.releaseTag.name])
    echo '[ReleaseSource] 平台上传及发布标签均完成，Source 已就绪'
}

def completeResources(String recordFile) {
    def record = readJSON file:recordFile, returnPojo: true
    if (record.schemaVersion != 1 || record.unity?.succeeded != true || record.unity?.cdnUploaded != true ||
        record.unity.packageDirectory)
        error '缺少成功的资源构建回执'
    publish(record.tag as Map)
}
