import org.july.release.SecretFiles

/** 共享构建模块：releaseCredentials。仅由流水线调用。 */
def requireProjectFields(Map fields, String credentialsPath) {
    def missing = fields.findAll { key, value ->
        value == null || "${value}".trim().isEmpty()
    }.keySet().toList()
    if (missing) {
        error "[BuildFarm] 项目凭证缺少必要字段: ${missing.join(', ')}；文件: ${credentialsPath}"
    }
}

def optionalPositiveInteger(def value, String fieldName) {
    if (value == null || "${value}".trim().isEmpty()) {
        return null
    }
    try {
        int parsed = "${value}".trim().toInteger()
        if (parsed < 1) {
            error "[BuildFarm] 项目凭证字段 ${fieldName} 必须是正整数"
        }
        return parsed
    } catch (NumberFormatException ignored) {
        error "[BuildFarm] 项目凭证字段 ${fieldName} 必须是正整数"
    }
}

def resolveProjectSecretFile(String configuredPath, String secretsRoot, String fieldName) {
    if (!configuredPath) {
        error "[BuildFarm] 项目凭证字段 ${fieldName} 不能为空"
    }
    def root = new File(secretsRoot).canonicalFile
    def file = new File(configuredPath)
    if (!file.isAbsolute()) {
        file = new File(root, configuredPath)
    }
    file = file.canonicalFile
    if (!file.path.toLowerCase().startsWith(root.path.toLowerCase() + File.separator)) {
        error "[BuildFarm] 项目凭证字段 ${fieldName} 必须指向项目 Secrets 内的文件: ${file}"
    }
    if (!file.isFile()) {
        error "[BuildFarm] 项目凭证字段 ${fieldName} 指向的文件不存在: ${file}"
    }
    SecretFiles.validate(file, fieldName)
    return file.canonicalPath
}

def withGitAuthentication(Closure action) {
    releaseJenkinsSecrets.withPair('git','GIT_USER','GIT_PASS') {
        // 文件仅引用环境变量，不写入账号或密码。
        writeFile file: '.jenkins_git_askpass.ps1', text: '''param([string]$Prompt)
if ($Prompt -match 'Username') {
    [Console]::Out.WriteLine($env:GIT_USER)
} else {
    [Console]::Out.WriteLine($env:GIT_PASS)
}
''', encoding: 'UTF-8'
        writeFile file: '.jenkins_git_askpass.cmd',
                  text: '@powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0.jenkins_git_askpass.ps1" "%~1"',
                  encoding: 'UTF-8'
        try {
            withEnv([
                "GIT_ASKPASS=${pwd()}\\.jenkins_git_askpass.cmd",
                'GIT_TERMINAL_PROMPT=0',
                // Jenkins 会删除空值环境变量；将空 helper 编码在非空字符串中。
                // COUNT=0 同时屏蔽继承的 indexed config，避免旧 helper 覆盖本设置。
                'GIT_CONFIG_COUNT=0',
                "GIT_CONFIG_PARAMETERS='credential.helper='"
            ]) { action() }
        } finally {
            bat '@del /q ".jenkins_git_askpass.cmd" ".jenkins_git_askpass.ps1" 2>nul'
        }
    }
}

def withFileCredential(String localPath, String secretsRoot,
                        String fieldName, String variableName, Closure action) {
    def resolved = resolveProjectSecretFile(localPath, secretsRoot, fieldName)
    withEnv(["${variableName}=${resolved}"]) {
        action()
    }
}

def withStringPair(String localFirst, String localSecond,
                    String firstVariable, String secondVariable, Closure action) {
    if (!localFirst || !localSecond) {
        error '[BuildFarm] 项目 JSON 中的成对字符串凭证不完整'
    }
    withEnv(["${firstVariable}=${localFirst}", "${secondVariable}=${localSecond}"]) {
        action()
    }
}

def withWeChatUploadCredentials(String localAppId, String localKeyPath,
                                 String secretsRoot, Closure action) {
    if (!localAppId) {
        error '[BuildFarm] 项目凭证字段 wechat.appId 不能为空'
    }
    def resolvedKey = resolveProjectSecretFile(localKeyPath, secretsRoot, 'wechat.uploadKeyPath')
    withEnv(["WX_APPID=${localAppId}", "WX_KEY_PATH=${resolvedKey}"]) {
        action()
    }
}

def withFeishuWebhook(String localWebhook, Closure action) {
    if (!localWebhook) {
        error '[BuildFarm] 项目凭证字段 feishu.webhook 不能为空'
    }
    action(localWebhook)
}
