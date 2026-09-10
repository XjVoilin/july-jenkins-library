/** 共享构建模块：releaseNotifications。仅由流水线调用。 */
def sendFeishu(String displayName, String color, String title, String extra,
                String coreVersion, String planVersion, String buildType,
                String platform, String buildEnvironment, boolean debug, String webhook) {
    def duration = currentBuild.durationString.replace(' and counting', '')
    def environmentLabels = [
        Dev: '开发（Dev）',
        Test: '测试（Test）',
        Prod: '生产（Prod）'
    ]
    def buildEnvironmentLabel = environmentLabels[buildEnvironment] ?: buildEnvironment ?: '未知'
    def debugEnabledLabel = debug ? '是' : '否'
    def body = """{
        "msg_type": "interactive",
        "card": {
            "header": {
                "title": {"tag": "plain_text", "content": "${title}"},
                "template": "${color}"
            },
            "elements": [
                {
                    "tag": "div",
                    "text": {
                        "tag": "lark_md",
                        "content": "**项目**: ${displayName}\\n**Core**: ${coreVersion}\\n**Plan**: ${planVersion}\\n**类型**: ${buildType}\\n**目标平台**: ${platform}\\n**构建环境**: ${buildEnvironmentLabel}\\n**是否开启 Debug**: ${debugEnabledLabel}\\n**构建号**: #${env.BUILD_NUMBER}\\n**耗时**: ${duration}${extra}"
                    }
                },
                {
                    "tag": "action",
                    "actions": [
                        {
                            "tag": "button",
                            "text": {"tag": "plain_text", "content": "查看构建日志"},
                            "url": "${env.BUILD_URL}console",
                            "type": "primary"
                        }
                    ]
                }
            ]
        }
    }"""

    try {
        doSendFeishu(webhook, body)
    } catch (e) {
        echo "[Feishu] 卡片消息发送失败 (${e.message})，降级为纯文本..."
        def fallback = """{
            "msg_type": "text",
            "content": {"text": "${title} | ${displayName} | Core ${coreVersion} | ${platform} | ${buildEnvironmentLabel} | Debug=${debugEnabledLabel} | #${env.BUILD_NUMBER} | ${duration}${extra}"}
        }"""
        doSendFeishu(webhook, fallback)
    }
}

def uploadFeishuImage(String imagePath, String localAppId, String localAppSecret) {
    def imageKey = ''
    releaseCredentials.withStringPair(localAppId, localAppSecret,
                    'FS_APP_ID', 'FS_APP_SECRET') {
        def escaped = imagePath.replace('\\', '/')
        writeFile file: '_feishu_upload.ps1', text: """
\$ErrorActionPreference = 'Stop'
\$tokenBody = @{ app_id = \$env:FS_APP_ID; app_secret = \$env:FS_APP_SECRET } | ConvertTo-Json
\$tokenResp = Invoke-RestMethod -Uri 'https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal' `
    -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes(\$tokenBody))
if (\$tokenResp.code -ne 0) { throw "Get token failed: \$(\$tokenResp.msg)" }
\$token = \$tokenResp.tenant_access_token

\$filePath = '${escaped}'
\$fileBytes = [System.IO.File]::ReadAllBytes(\$filePath)
\$fileName = [System.IO.Path]::GetFileName(\$filePath)
\$boundary = [System.Guid]::NewGuid().ToString()
\$body = "--\$boundary`r`n"
\$body += "Content-Disposition: form-data; name=`"image_type`"`r`n`r`nmessage`r`n"
\$body += "--\$boundary`r`n"
\$body += "Content-Disposition: form-data; name=`"image`"; filename=`"\$fileName`"`r`n"
\$body += "Content-Type: image/jpeg`r`n`r`n"
\$headerBytes = [System.Text.Encoding]::UTF8.GetBytes(\$body)
\$footerBytes = [System.Text.Encoding]::UTF8.GetBytes("`r`n--\$boundary--`r`n")
\$ms = New-Object System.IO.MemoryStream
\$ms.Write(\$headerBytes, 0, \$headerBytes.Length)
\$ms.Write(\$fileBytes, 0, \$fileBytes.Length)
\$ms.Write(\$footerBytes, 0, \$footerBytes.Length)
\$payload = \$ms.ToArray()
\$ms.Close()
\$resp = Invoke-RestMethod -Uri 'https://open.feishu.cn/open-apis/im/v1/images' `
    -Method Post -Headers @{ Authorization = "Bearer \$token" } `
    -ContentType "multipart/form-data; boundary=\$boundary" -Body \$payload
if (\$resp.code -ne 0) { throw "Upload image failed: \$(\$resp.msg)" }
Write-Output \$resp.data.image_key
""", encoding: 'UTF-8'
        imageKey = powershell(returnStdout: true, script: '& ./_feishu_upload.ps1').trim()
    }
    return imageKey
}

def sendFeishuResplit(String displayName, String color, String title,
                       String version, String platform, String webhook,
                       String imageKey = '', String extra = '') {
    def duration = currentBuild.durationString.replace(' and counting', '')
    def imageElement = imageKey ? """,
                {
                    "tag": "div",
                    "text": {"tag": "lark_md", "content": "**扫码采集 profile/prepare 包**（二维码过期后可在 Jenkins 刷新）"}
                },
                {
                    "tag": "img", "img_key": "${imageKey}",
                    "alt": {"tag": "plain_text", "content": "代码分包采集二维码"},
                    "compact_width": true, "mode": "crop_center", "custom_width": 280, "preview": true
                }""" : ''
    def body = """{
        "msg_type": "interactive",
        "card": {
            "header": {"title": {"tag": "plain_text", "content": "${title}"}, "template": "${color}"},
            "elements": [
                {"tag": "div", "text": {"tag": "lark_md",
                 "content": "**项目**: ${displayName}\\n**版本**: ${version}\\n**平台**: ${platform}\\n**构建号**: #${env.BUILD_NUMBER}\\n**耗时**: ${duration}${extra}"}}
                ${imageElement},
                {"tag": "action", "actions": [
                    {"tag": "button", "text": {"tag": "plain_text", "content": "查看 Jenkins 日志"},
                     "url": "${env.BUILD_URL}console", "type": "primary"}
                ]}
            ]
        }
    }"""
    try {
        doSendFeishu(webhook, body)
    } catch (e) {
        echo "[Feishu] 卡片发送失败 (${e.message})，降级为纯文本"
        def fallback = """{"msg_type":"text","content":{"text":"${title} | ${displayName} | ${version} | ${platform} | #${env.BUILD_NUMBER}"}}"""
        doSendFeishu(webhook, fallback)
    }
}

def doSendFeishu(String webhook, String body) {
    writeFile file:'feishu_payload.json',text:body,encoding:'UTF-8'
    withEnv(["FS_WEBHOOK=$webhook"]) {
        powershell '''
            $ErrorActionPreference = 'Stop'
            $bytes = [IO.File]::ReadAllBytes('feishu_payload.json')
            $resp = Invoke-RestMethod -Uri $env:FS_WEBHOOK -Method Post -ContentType 'application/json; charset=utf-8' -Body $bytes
            if ($resp.code -ne 0) { throw "Feishu response code: $($resp.code)" }
        '''
    }
}
