import org.july.release.ReleaseStorage
def update(String path, Map changes) {
    ReleaseStorage.update(new File(path), changes)
}
def once(String path, String operation, Closure work) {
    def checkpoint = ReleaseStorage.begin(new File(path), operation, env.BUILD_URL ?: env.BUILD_TAG)
    runOperation(path, operation, checkpoint, work)
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
    // 异常或中断后保留 STARTED。仅 finalizePackage 可在下一次构建重试，采集/上传仍需核对。
    def result = work()
    ReleaseStorage.complete(new File(path), operation, result)
    result
}
