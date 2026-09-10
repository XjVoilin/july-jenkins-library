package org.july.release
import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Windows 单机：Controller 可访问 BuildFarm；不隐式支持远程 Agent。 */
class ReleaseStorage {
    @NonCPS static File inside(File root, File target) {
        root = root.canonicalFile
        target = target.canonicalFile
        if (target == root || !target.toPath().startsWith(root.toPath())) {
            throw new IllegalArgumentException("目录越界: $target")
        }
        target
    }
    @NonCPS static Map read(File file) {
        try {
            def value = new JsonSlurperClassic().parse(file, 'UTF-8')
            if (!(value instanceof Map)) throw new IllegalArgumentException()
            return (Map)value
        } catch (Exception ignored) {
            throw new IllegalStateException("JSON 文件缺失或格式错误: $file")
        }
    }
    @NonCPS static void writeAtomic(File file, Map value) {
        file = file.canonicalFile
        if (!file.parentFile.isDirectory()) throw new IllegalStateException("父目录不存在: $file")
        def tmp = Files.createTempFile(file.parentFile.toPath(), '.release-', '.tmp')
        try {
            Files.write(tmp, (JsonOutput.prettyPrint(JsonOutput.toJson(value)) + '\n').getBytes('UTF-8'))
            Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(tmp) }
    }
    @NonCPS static Map acquire(File projectRoot, String owner) {
        if (!owner) throw new IllegalArgumentException('占用者不能为空')
        Files.createDirectories(projectRoot.canonicalFile.toPath())
        def file = inside(projectRoot, new File(projectRoot, '.jenkins-project.lock'))
        def lease = [path:file.path, token:UUID.randomUUID().toString(), owner:owner,
                     createdAt:new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")]
        try {
            Files.write(file.toPath(), JsonOutput.toJson(lease).getBytes('UTF-8'),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            throw new IllegalStateException("项目正在被占用，或上次任务异常退出。请检查 ${file}；禁止自动抢占。")
        }
        lease
    }
    @NonCPS static void release(Map lease) {
        if (!lease) return
        def file = new File(lease.path.toString())
        if (!file.isFile() || read(file).token != lease.token) {
            throw new IllegalStateException("占用凭据不匹配，拒绝释放: $file")
        }
        Files.delete(file.toPath())
    }
    @NonCPS static String reserve(File root, String platform, String version, String build) {
        if (!(platform in ['WeChat','TikTok']) || !(version ==~ /\d+\.\d+\.\d+/) || !(build ==~ /[1-9]\d*/)) {
            throw new IllegalArgumentException('Source 平台、版本或构建号非法')
        }
        def dir = inside(root, new File(root, "$platform/$version/$build"))
        Files.createDirectories(dir.parentFile.toPath())
        try { Files.createDirectory(dir.toPath()) }
        catch (java.nio.file.FileAlreadyExistsException ignored) {
            throw new IllegalStateException("Source 已存在，禁止覆盖（FORCE_REBUILD 也不例外）: $dir")
        }
        dir.path
    }
    /** Jenkins 只绑定 Source 内的 AOT 目录，不解释框架内部格式或恢复文件。 */
    @NonCPS static String aotPath(File sourceRoot, String configuredPath = null, boolean requireFiles = false) {
        def expected = inside(sourceRoot, new File(sourceRoot, 'aot'))
        if (configuredPath && new File(configuredPath).canonicalFile != expected)
            throw new IllegalStateException('AOT 路径必须指向所选 Source 自身的 aot 目录')
        if (requireFiles) {
            if (!expected.isDirectory())
                throw new IllegalStateException('框架未生成指定 AOT 快照；请确认 com.july.release 已支持显式路径参数')
            boolean found = false
            expected.eachFileRecurse(groovy.io.FileType.FILES) { found = true }
            if (!found) throw new IllegalStateException('框架生成的 AOT 快照为空，拒绝将 Source 标记为可用')
        }
        expected.path
    }
    @NonCPS static Map update(File file, Map changes) {
        def state = read(file)
        ['schemaVersion','buildTarget','projectName','platform','version','sourceBuildNumber','rawPackagePath',
         'rawWasmMd5','packageFingerprint','aotBackupPath','aotBackupFingerprint',
         'buildEnvironment','debug','sourceGitCommit'].each { key ->
            if (changes.containsKey(key) && changes[key] != state[key]) {
                throw new IllegalArgumentException("禁止修改 Source 基线字段: $key")
            }
        }
        def transitions = [SNAPSHOTTED:['SOURCE_READY'],SOURCE_READY:['COLLECTING'],
                           COLLECTING:['UPLOADED'],UPLOADED:['COLLECTING']]
        if (changes.state && changes.state != state.state &&
            !(changes.state in (transitions[state.state] ?: []))) {
            throw new IllegalStateException('非法 Source 状态流转: ' + state.state + ' -> ' + changes.state)
        }
        state.putAll(changes)
        state.updatedAt = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('Asia/Shanghai'))
        writeAtomic(file, state)
        state
    }
    @NonCPS static Map begin(File file, String key, String owner) {
        beginOperation(file, key, owner, false)
    }
    /** 仅正式分包允许重做；调用方必须持有项目锁，平台适配器从干净副本执行 dosplit。 */
    @NonCPS static Map beginFinalization(File file, String key, String owner) {
        beginOperation(file, key, owner, true)
    }
    @NonCPS private static Map beginOperation(File file, String key, String owner, boolean retryFinalization) {
        def state = read(file)
        Map operations = state.operations ?: [:]
        def previous = operations[key]
        if (retryFinalization && (!(state.platform in ['WeChat','TikTok']) ||
            state.state != 'COLLECTING' || !(state.collectionCycle instanceof Number) ||
            state.collectionCycle < 1 || key != "finalize-${state.collectionCycle}")) {
            throw new IllegalStateException('仅允许重试当前采集周期的正式分包')
        }
        if (previous?.status == 'DONE') return [done:true, result:previous.result]
        if (retryFinalization && operations.containsKey("upload-${state.collectionCycle}".toString())) {
            throw new IllegalStateException('本周期已有上传记录，禁止重新生成正式分包；请先核对上传结果')
        }
        if (operations.any { name, op -> op.status == 'STARTED' && !(retryFinalization && name == key) }) {
            throw new IllegalStateException("Source 有未确认的外部操作，禁止盲目重试: ${file}；请核对平台结果后恢复。")
        }
        if (previous && previous.status != 'STARTED') {
            throw new IllegalStateException("未知操作状态，拒绝覆盖: $key")
        }
        long now = new Date().time
        def next = [status:'STARTED', owner:owner, startedAt:now]
        if (previous) {
            // 保留旧 owner/时间等证据，使用平铺历史，避免每次重试嵌套整个历史。
            def history = new ArrayList(previous.previousAttempts ?: [])
            def abandoned = new LinkedHashMap(previous)
            abandoned.remove('previousAttempts')
            history.add(abandoned + [supersededAt:now])
            next.previousAttempts = history
        }
        operations[key] = next
        update(file, [operations:operations])
        [done:false, retriedOwner:previous?.owner]
    }
    @NonCPS static void complete(File file, String key, Object result) {
        def state = read(file)
        Map operations = state.operations ?: [:]
        if (operations[key]?.status != 'STARTED') throw new IllegalStateException("操作未开始: $key")
        operations[key] = operations[key] + [status:'DONE', result:result, completedAt:new Date().time]
        update(file, [operations:operations])
    }
}
