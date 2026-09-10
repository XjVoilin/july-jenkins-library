import com.cloudbees.groovy.cps.NonCPS
import org.july.release.TemplateText
import com.cloudbees.hudson.plugins.folder.Folder
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.job.WorkflowJob

/**
 * 使用共享库中的专用 XML 模板，为一个 CoreVersion 创建 FullBuild / HotUpdate Job。
 *
 * 模板位于 resources/unity-minigame，不依赖任何历史发布 Job。
 * 生成器只创建 Job，不触发构建、不覆盖已有 Job。
 */
def call(Map config) {
    assert config.projectName : 'projectName is required'

    def projectName = config.projectName.toString()
    def displayName = (config.displayName ?: projectName).toString()
    def defaultVersion = (config.defaultVersion ?: '').toString()
    def buildClass = (config.buildClass ?: 'July.Release.Editor.BuildPipelineCI').toString()
    def platforms = (config.platforms ?: ['WeChat', 'TikTok']).collect { it.toString() }
    assert config.repoUrl : 'repoUrl is required'
    def repoUrl = config.repoUrl.toString()
    def buildFarmRoot = (config.buildFarmRoot ?: 'D:\\BuildFarm').toString()
    def jobFolder = projectName
    def credentialsFile = (config.credentialsFile ?:
        "${buildFarmRoot}\\${projectName}\\Secrets\\credentials.local.json").toString()
    properties([
        disableConcurrentBuilds(),
        parameters([
            string(
                name: 'CORE_VERSION',
                defaultValue: defaultVersion,
                trim: true,
                description: '要创建的核心版本（x.y.z）；会同时生成全量和热更 Job，不会触发构建',
            ),
        ]),
    ])

    stage('生成版本 Job') {
        def coreVersion = (params.CORE_VERSION ?: '').trim()
        validateConfig(projectName, coreVersion, buildClass, platforms)

        def template = libraryResource('unity-minigame/pipeline-job.xml.tpl')
        def hotSourceScript = unityMiniGameSourceScript(
            kind: 'hot-update', projectName: projectName, coreVersion: coreVersion,
            buildFarmRoot: buildFarmRoot, platforms: platforms)
        def fullLeafName = "${coreVersion}_FullBuild"
        def hotLeafName = "${coreVersion}_HotUpdate"
        def fullJobName = "${jobFolder}/${fullLeafName}"
        def hotJobName = "${jobFolder}/${hotLeafName}"
        def fullPipelineScript = pipelineScript(
            projectName, "${displayName} v${coreVersion} 全量", buildClass,
            platforms, repoUrl, coreVersion, 'FullBuild',
            buildFarmRoot, credentialsFile)
        def hotPipelineScript = pipelineScript(
            projectName, "${displayName} v${coreVersion} 热更", buildClass,
            platforms, repoUrl, coreVersion, 'HotUpdateBuild',
            buildFarmRoot, credentialsFile)

        def fullXml = TemplateText.renderTemplate(template, [
            DESCRIPTION_XML: TemplateText.xmlEscape("由专用模板生成：${displayName} v${coreVersion} 全量"),
            PARAMETER_DEFINITIONS_XML: releaseParameters.xml(
                releaseParameters.definitions('full-build', [platforms:platforms])),
            PIPELINE_SCRIPT_XML: TemplateText.xmlEscape(fullPipelineScript),
        ])
        def hotXml = TemplateText.renderTemplate(template, [
            DESCRIPTION_XML: TemplateText.xmlEscape("由专用模板生成：${displayName} v${coreVersion} 热更"),
            PARAMETER_DEFINITIONS_XML: releaseParameters.xml(
                releaseParameters.definitions('hot-update',
                    [platforms:platforms,coreVersion:coreVersion,sourceScript:hotSourceScript])),
            PIPELINE_SCRIPT_XML: TemplateText.xmlEscape(hotPipelineScript),
        ])

        def result = createVersionJobs(
            jobFolder, fullLeafName, hotLeafName, fullJobName, hotJobName,
            fullXml, hotXml, buildClass, coreVersion)
        currentBuild.description = "${coreVersion} 全量 + 热更"
        echo "已创建：${result.fullJob}"
        echo "已创建：${result.hotUpdateJob}"
        echo '本次只生成 Job，未触发任何 Unity 构建。'
    }
}

@NonCPS
private void validateConfig(String projectName, String coreVersion,
                            String buildClass, List<String> platforms) {
    if (coreVersion == '99.99.99') {
        throw new IllegalArgumentException('99.99.99 保留给项目 QA Job，请使用 <项目>/QA')
    }
    if (!(coreVersion ==~ /[0-9]+[.][0-9]+[.][0-9]+/)) {
        throw new IllegalArgumentException(
            "CORE_VERSION 格式错误: '${coreVersion}'，需要 x.y.z（例如 1.7.0）")
    }
    if (!(projectName ==~ /[A-Za-z0-9][A-Za-z0-9_.-]*/)) {
        throw new IllegalArgumentException(
            "projectName 只允许字母、数字、下划线、点和短横线: '${projectName}'")
    }
    if (!(buildClass ==~ /[A-Za-z_][A-Za-z0-9_.]*/)) {
        throw new IllegalArgumentException("buildClass 格式错误: '${buildClass}'")
    }
    if (platforms.isEmpty() || platforms.any { !(it in ['WeChat', 'TikTok']) }) {
        throw new IllegalArgumentException("platforms 格式错误: ${platforms}")
    }
}

@NonCPS
private Map createVersionJobs(String jobFolder,
                              String fullLeafName, String hotLeafName,
                              String fullJobName, String hotJobName,
                              String fullXml, String hotXml,
                              String buildClass, String coreVersion) {
    def jenkins = Jenkins.get()
    if (jenkins.getItemByFullName(fullJobName) != null ||
        jenkins.getItemByFullName(hotJobName) != null) {
        throw new IllegalStateException(
            "目标 Job 已存在，拒绝覆盖：${fullJobName} / ${hotJobName}")
    }

    validateRenderedConfig(fullXml, buildClass, coreVersion)
    validateRenderedConfig(hotXml, buildClass, coreVersion)

    def container = jenkins.getItemByFullName(jobFolder)
    if (!(container instanceof Folder)) {
        throw new IllegalStateException("项目 Folder 不存在: $jobFolder")
    }

    def created = []
    try {
        created << createFromXml(container, fullLeafName, fullXml)
        created << createFromXml(container, hotLeafName, hotXml)
    } catch (Throwable failure) {
        created.reverseEach { it.delete() }
        throw failure
    }

    return [fullJob: fullJobName, hotUpdateJob: hotJobName]
}

@NonCPS
private void validateRenderedConfig(String xml, String buildClass, String coreVersion) {
    if (xml =~ /@@[A-Z0-9_]+@@/) {
        throw new IllegalStateException('专用模板仍包含未替换占位符')
    }
    if (!xml.contains(buildClass) || !xml.contains(coreVersion)) {
        throw new IllegalStateException('专用模板缺少构建入口或核心版本')
    }
    if (!xml.contains('<sandbox>true</sandbox>') ||
        !xml.contains('<disabled>false</disabled>')) {
        throw new IllegalStateException('专用模板的 Pipeline 安全设置不符合预期')
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
private String pipelineScript(String projectName, String displayName,
                              String buildClass, List<String> platforms,
                              String repoUrl, String coreVersion,
                              String buildType,
                              String buildFarmRoot, String credentialsFile) {
    def lines = [
        "@Library('unity-minigame') _",
        'unityMiniGamePipeline(',
        "    projectName:      ${TemplateText.groovyString(projectName)},",
        "    displayName:      ${TemplateText.groovyString(displayName)},",
        "    buildClass:       ${TemplateText.groovyString(buildClass)},",
        "    platforms:        ${TemplateText.groovyList(platforms)},",
        "    repoUrl:          ${TemplateText.groovyString(repoUrl)},",
        "    coreVersion:      ${TemplateText.groovyString(coreVersion)},",
        "    buildType:        ${TemplateText.groovyString(buildType)},",
        "    buildFarmRoot:    ${TemplateText.groovyString(buildFarmRoot)},",
        "    credentialsFile:  ${TemplateText.groovyString(credentialsFile)},",
    ]
    lines << ')'
    return lines.join('\n')
}
