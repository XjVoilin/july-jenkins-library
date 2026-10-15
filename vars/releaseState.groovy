import org.july.release.ReleaseStorage
def update(String path, Map changes) {
    ReleaseStorage.update(new File(path), changes)
}
def once(String path, String operation, Closure work) {
    def checkpoint = ReleaseStorage.begin(new File(path), operation, env.BUILD_URL ?: env.BUILD_TAG)
    runOperation(path, operation, checkpoint, work)
}

/** work 在准备完成、发出首个平台命令前调用 start；准备失败不产生检查点。 */
def oncePrepared(String path, String operation, Closure work) {
    def checkpoint = ReleaseStorage.check(new File(path), operation)
    runOperation(path, operation, checkpoint) {
        work { ReleaseStorage.begin(new File(path), operation, env.BUILD_URL ?: env.BUILD_TAG) }
    }
}

/** 仅续跑当前周期上传；先复用已校验的分包，登录成功后才开始新的上传尝试。 */
def uploadPackage(String path, String operation, Closure work) {
    def checkpoint = ReleaseStorage.checkUpload(new File(path), operation)
    runOperation(path, operation, checkpoint) {
        work {
            def attempt = ReleaseStorage.beginUpload(new File(path), operation, env.BUILD_URL ?: env.BUILD_TAG)
            if (attempt.retriedOwner) {
                echo "[Source] 续跑未完成的上传: ${operation}；上次任务 ${attempt.retriedOwner}，记录已保留。"
                echo '[Source] 复用已校验的正式分包重新上传；上次上传可能已到达平台，以本次成功回执为准。'
            }
        }
    }
}

def finalizePackage(String path, String operation, Closure work) {
    def checkpoint = ReleaseStorage.beginFinalization(new File(path), operation, env.BUILD_URL ?: env.BUILD_TAG)
    if (checkpoint.retriedOwner) {
        echo "[Source] 重试未完成的正式分包: ${operation}；上次任务 ${checkpoint.retriedOwner}，记录已保留。"
        echo '[Source] 从干净副本重新执行平台 dosplit；可能继续下载或生成新版本，不撤销旧远端版本。'
    }
    runOperation(path, operation, checkpoint, work)
}

private def runOperation(String path, String operation, Map checkpoint, Closure work) {
    if (checkpoint.done) {
        echo "[Source] 复用已完成步骤: $operation"
        return checkpoint.result
    }
    // 异常或中断后保留 STARTED。正式分包/上传通过专用入口续跑，采集仍需核对。
    def result = work()
    ReleaseStorage.complete(new File(path), operation, result)
    result
}
