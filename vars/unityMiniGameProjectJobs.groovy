import com.cloudbees.groovy.cps.NonCPS
import org.july.release.TemplateText
import com.cloudbees.hudson.plugins.folder.Folder
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.job.WorkflowJob

/**
 * 初始化一个使用 july-jenkins-library 共享构建流程的新项目。
 *
 * 创建：
 *   Jenkins/<项目>/CreateVersionJobs
 *   Jenkins/<项目>/CodeSplit
 *   Jenkins/<项目>/QA（固定 99.99.99 的全量构建）
 *   D:\BuildFarm\<项目>\{Workspace,Build,ReleaseSources,Cache,Secrets}
 *
 * 初始化项目和固定 QA Job，不创建正式版本 Job、不拉取代码、不执行 Unity 构建。
 */
def call(Map config = [:]) {
    def buildFarmRoot = (config.buildFarmRoot ?: 'D:\\BuildFarm').toString()
    def agentLabel = (config.agentLabel ?: 'unity').toString()
    def defaultBuildClass =
        (config.buildClass ?: 'July.Release.Editor.BuildPipelineCI').toString()
    def defaultPlatforms =
        (config.platforms ?: ['WeChat', 'TikTok']).collect { it.toString() }
    def defaultVersion = (config.defaultVersion ?: '1.0.0').toString()

    properties([
        disableConcurrentBuilds(),
        parameters([
            string(
                name: 'PROJECT_NAME',
                defaultValue: '',
                trim: true,
                description: '项目唯一标识，同时作为 Jenkins Folder 和 D:\\BuildFarm 下的目录名；仅允许字母、数字、下划线、点和短横线',
            ),
            string(
                name: 'DISPLAY_NAME',
                defaultValue: '',
                trim: true,
                description: '项目显示名称；留空时使用 PROJECT_NAME',
            ),
            string(
                name: 'REPO_URL',
                defaultValue: '',
                trim: true,
                description: '项目 Git 仓库地址',
            ),
            string(
                name: 'INITIAL_CORE_VERSION',
                defaultValue: defaultVersion,
                trim: true,
                description: 'CreateVersionJobs 页面默认填入的版本；本次初始化不会创建版本 Job',
            ),
            string(
                name: 'PLATFORMS',
                defaultValue: defaultPlatforms.join(','),
                trim: true,
                description: '允许的平台，英文逗号分隔',
            ),
        ]),
    ])

    def projectName = (params.PROJECT_NAME ?: '').trim()
    def displayName = (params.DISPLAY_NAME ?: projectName).trim()
    def repoUrl = (params.REPO_URL ?: '').trim()
    def initialVersion = (params.INITIAL_CORE_VERSION ?: '').trim()
    def buildClass = defaultBuildClass
    def platforms = parsePlatforms((params.PLATFORMS ?: '').toString())
    def projectRoot = "${buildFarmRoot}\\${projectName}"
    def credentialsFile = "${projectRoot}\\Secrets\\credentials.local.json"

    stage('校验初始化参数') {
        validateConfig(
            projectName, displayName, repoUrl, initialVersion,
            buildClass, platforms, buildFarmRoot, agentLabel)
        assertProjectDoesNotExist(projectName)

        node(agentLabel) {
            if (fileExists(projectRoot)) {
                error("项目目录已存在，拒绝覆盖：${projectRoot}")
            }
        }
    }

    def createVersionScript = versionGeneratorScript(
        projectName, displayName, buildClass, platforms, repoUrl,
        initialVersion, buildFarmRoot, credentialsFile)
    def codeSplitScript = codeSplitPipelineScript(
        projectName, displayName, platforms,
        buildFarmRoot, credentialsFile)
    def codeSplitSource = unityMiniGameSourceScript(
        kind: 'code-split', projectName: projectName,
        buildFarmRoot: buildFarmRoot, platforms: platforms)

    def qaXml = qaJobXml(projectName, displayName, buildClass, platforms,
        repoUrl, buildFarmRoot, credentialsFile)
    def createVersionXml = TemplateText.renderTemplate(
        libraryResource('july-jenkins-library/create-version-jobs.xml.tpl'),
        [
            DESCRIPTION_XML: TemplateText.xmlEscape("${displayName}：每次为一个核心版本创建全量与热更 Job"),
            INITIAL_CORE_VERSION_XML: TemplateText.xmlEscape(initialVersion),
            PIPELINE_SCRIPT_XML: TemplateText.xmlEscape(createVersionScript),
        ])
    def codeSplitXml = TemplateText.renderTemplate(
        libraryResource('july-jenkins-library/pipeline-job.xml.tpl'),
        [
            DESCRIPTION_XML: TemplateText.xmlEscape("${displayName}：微信/抖音小游戏代码分包"),
            PARAMETER_DEFINITIONS_XML: releaseParameters.xml(
                releaseParameters.definitions('code-split',
                    [platforms:platforms,sourceScript:codeSplitSource])),
            PIPELINE_SCRIPT_XML: TemplateText.xmlEscape(codeSplitScript),
        ])

    stage('创建 Jenkins 项目 Folder') {
        createProjectJobs(
            projectName, displayName, createVersionXml, codeSplitXml, qaXml)
    }

    try {
        stage('创建项目级目录与凭证模板') {
            node(agentLabel) {
                createProjectDirectory(
                    projectRoot, credentialsFile, buildFarmRoot)
            }
        }
    } catch (Throwable failure) {
        deleteProjectFolder(projectName)
        throw failure
    }

    currentBuild.description = projectName
    echo "项目初始化完成：${projectName}"
    echo "版本任务入口：${projectName}/CreateVersionJobs"
    echo "代码分包入口：${projectName}/CodeSplit"
    echo "QA 入口：${projectName}/QA；固定版本及分支：99.99.99 / Tuanjie_Build/99.99.99"
    echo "本次未创建正式版本 Job，也未执行构建。"
    echo "请填写 Secrets 内的 JSON、两个微信密钥文件及 cos.yaml：${projectRoot}\\Secrets"
}

private List<String> parsePlatforms(String raw) {
    return raw.split(',')
        .collect { it.trim() }
        .findAll { it }
        .unique()
}

@NonCPS
private void validateConfig(String projectName, String displayName,
                            String repoUrl, String initialVersion,
                            String buildClass, List<String> platforms,
                            String buildFarmRoot, String agentLabel) {
    if (!(projectName ==~ /[A-Za-z0-9][A-Za-z0-9_.-]*/)) {
        throw new IllegalArgumentException(
            "PROJECT_NAME 只允许字母、数字、下划线、点和短横线: '${projectName}'")
    }
    if (!displayName) {
        throw new IllegalArgumentException('DISPLAY_NAME 不能为空')
    }
    if (!repoUrl) {
        throw new IllegalArgumentException('REPO_URL 不能为空')
    }
    if (initialVersion == '99.99.99') {
        throw new IllegalArgumentException('99.99.99 保留给自动创建的 QA Job；正式版本请填写其他版本号')
    }
    if (!(initialVersion ==~ /[0-9]+[.][0-9]+[.][0-9]+/)) {
        throw new IllegalArgumentException(
            "INITIAL_CORE_VERSION 格式错误: '${initialVersion}'，需要 x.y.z")
    }
    if (!(buildClass ==~ /[A-Za-z_][A-Za-z0-9_.]*/)) {
        throw new IllegalArgumentException("BUILD_CLASS 格式错误: '${buildClass}'")
    }
    if (platforms.isEmpty() ||
        platforms.any { !(it in ['WeChat', 'TikTok']) }) {
        throw new IllegalArgumentException("PLATFORMS 格式错误: ${platforms}")
    }
    if (!buildFarmRoot || buildFarmRoot.contains('"') ||
        buildFarmRoot.contains('\n') || buildFarmRoot.contains('\r')) {
        throw new IllegalArgumentException('buildFarmRoot 格式错误')
    }
    if (!(agentLabel ==~ /[A-Za-z0-9_. -]+/)) {
        throw new IllegalArgumentException("agentLabel 格式错误: '${agentLabel}'")
    }
}

@NonCPS
private void assertProjectDoesNotExist(String projectName) {
    if (Jenkins.get().getItemByFullName(projectName) != null) {
        throw new IllegalStateException(
            "Jenkins 项目或 Job 已存在，拒绝覆盖：${projectName}")
    }
}

@NonCPS
private void createProjectJobs(String projectName, String displayName,
                               String createVersionXml, String codeSplitXml, String qaXml) {
    validateRenderedConfig(createVersionXml)
    validateRenderedConfig(codeSplitXml)
    validateRenderedConfig(qaXml)

    def jenkins = Jenkins.get()
    if (jenkins.getItemByFullName(projectName) != null) {
        throw new IllegalStateException(
            "Jenkins 项目或 Job 已存在，拒绝覆盖：${projectName}")
    }

    Folder folder = null
    try {
        folder = jenkins.createProject(Folder.class, projectName)
        folder.setDisplayName(displayName)
        folder.setDescription(
            "${displayName} 构建任务；项目目录 D:\\BuildFarm\\${projectName}")
        folder.save()

        createFromXml(folder, 'CreateVersionJobs', createVersionXml)
        createFromXml(folder, 'CodeSplit', codeSplitXml)
        createFromXml(folder, 'QA', qaXml)
    } catch (Throwable failure) {
        if (folder != null) {
            folder.delete()
        }
        throw failure
    }
}

@NonCPS
private WorkflowJob createFromXml(def container, String name, String xml) {
    def input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
    try {
        return (WorkflowJob)container.createProjectFromXML(name, input)
    } finally {
        input.close()
    }
}

@NonCPS
private void deleteProjectFolder(String projectName) {
    def item = Jenkins.get().getItemByFullName(projectName)
    if (item instanceof Folder) {
        item.delete()
    }
}

private void createProjectDirectory(String projectRoot, String credentialsFile,
                                    String buildFarmRoot) {
    def jsonTemplate = libraryResource('july-jenkins-library/credentials.local.json.tpl')
    def cosTemplate = libraryResource('july-jenkins-library/cos.yaml.tpl')
    try {
        bat(
            label: '初始化 BuildFarm 项目目录',
            script: """@echo off
if exist "${projectRoot}" (
  echo 项目目录已存在，拒绝覆盖：${projectRoot}
  exit /b 2
)
if not exist "${buildFarmRoot}" mkdir "${buildFarmRoot}"
if errorlevel 1 exit /b 3
mkdir "${projectRoot}"
if errorlevel 1 exit /b 3
type nul > "${projectRoot}\\.jenkins-project-initializing"
if errorlevel 1 exit /b 3
mkdir "${projectRoot}\\Workspace"
if errorlevel 1 exit /b 3
mkdir "${projectRoot}\\Build"
if errorlevel 1 exit /b 3
mkdir "${projectRoot}\\ReleaseSources"
if errorlevel 1 exit /b 3
mkdir "${projectRoot}\\Cache"
if errorlevel 1 exit /b 3
mkdir "${projectRoot}\\Secrets"
if errorlevel 1 exit /b 3
""")
        writeFile file: credentialsFile, text: jsonTemplate, encoding: 'UTF-8'
        writeFile file: "${projectRoot}/Secrets/wechat-upload.key", text: '', encoding: 'UTF-8'
        writeFile file: "${projectRoot}/Secrets/wechat-wasm-split.key", text: '', encoding: 'UTF-8'
        writeFile file: "${projectRoot}/Secrets/cos.yaml", text: cosTemplate, encoding: 'UTF-8'
        bat(
            label: '完成项目初始化',
            script: """@echo off
del /Q "${projectRoot}\\.jenkins-project-initializing"
if errorlevel 1 exit /b 3
""")
    } catch (Throwable failure) {
        bat(
            label: '回滚未完成的项目目录',
            script: """@echo off
if exist "${projectRoot}\\.jenkins-project-initializing" rmdir /S /Q "${projectRoot}"
""")
        throw failure
    }
}

/** QA 复用全量流水线和参数定义，不增加框架入口或版本输入框。 */
private String qaJobXml(String projectName, String displayName, String buildClass,
                        List<String> platforms, String repoUrl,
                        String buildFarmRoot, String credentialsFile) {
    def script = [
        "@Library('july-jenkins-library') _",
        'unityMiniGamePipeline(',
        "    projectName: ${TemplateText.groovyString(projectName)},",
        "    displayName: ${TemplateText.groovyString(displayName + ' QA 99.99.99')},",
        "    buildClass: ${TemplateText.groovyString(buildClass)},",
        "    platforms: ${TemplateText.groovyList(platforms)},",
        "    repoUrl: ${TemplateText.groovyString(repoUrl)},",
        "    coreVersion: '99.99.99',",
        "    buildType: 'FullBuild',",
        "    buildFarmRoot: ${TemplateText.groovyString(buildFarmRoot)},",
        "    credentialsFile: ${TemplateText.groovyString(credentialsFile)},",
        ')'
    ].join('\n')
    return TemplateText.renderTemplate(libraryResource('july-jenkins-library/pipeline-job.xml.tpl'), [
        DESCRIPTION_XML: TemplateText.xmlEscape(displayName +
            '：QA 全量包；固定版本 99.99.99，分支 Tuanjie_Build/99.99.99；上传及归档规则同全量构建'),
        PARAMETER_DEFINITIONS_XML: releaseParameters.xml(
            releaseParameters.definitions('full-build', [platforms:platforms])),
        PIPELINE_SCRIPT_XML: TemplateText.xmlEscape(script)
    ])
}

@NonCPS
private String versionGeneratorScript(
    String projectName, String displayName, String buildClass,
    List<String> platforms, String repoUrl, String initialVersion,
    String buildFarmRoot, String credentialsFile) {
    def lines = [
        "@Library('july-jenkins-library') _",
        'unityMiniGameVersionJobs(',
        "    projectName:      ${TemplateText.groovyString(projectName)},",
        "    displayName:      ${TemplateText.groovyString(displayName)},",
        "    buildClass:       ${TemplateText.groovyString(buildClass)},",
        "    platforms:        ${TemplateText.groovyList(platforms)},",
        "    repoUrl:          ${TemplateText.groovyString(repoUrl)},",
        "    defaultVersion:   ${TemplateText.groovyString(initialVersion)},",
        "    buildFarmRoot:    ${TemplateText.groovyString(buildFarmRoot)},",
        "    credentialsFile:  ${TemplateText.groovyString(credentialsFile)},",
    ]
    lines << ')'
    return lines.join('\n')
}

@NonCPS
private String codeSplitPipelineScript(
    String projectName, String displayName, List<String> platforms,
    String buildFarmRoot, String credentialsFile) {
    def lines = [
        "@Library('july-jenkins-library') _",
        'codeSplitPipeline(',
        "    projectName:      ${TemplateText.groovyString(projectName)},",
        "    displayName:      ${TemplateText.groovyString(displayName + ' CodeSplit')},",
        "    platforms:        ${TemplateText.groovyList(platforms)},",
        "    buildFarmRoot:    ${TemplateText.groovyString(buildFarmRoot)},",
        "    credentialsFile:  ${TemplateText.groovyString(credentialsFile)},",
    ]
    lines << ')'
    return lines.join('\n')
}

@NonCPS
private void validateRenderedConfig(String xml) {
    if (xml =~ /@@[A-Z0-9_]+@@/) {
        throw new IllegalStateException('初始化模板仍包含未替换占位符')
    }
    if (!xml.contains('<sandbox>true</sandbox>') ||
        !xml.contains('<disabled>false</disabled>')) {
        throw new IllegalStateException('初始化模板的 Pipeline 安全设置不符合预期')
    }
}
