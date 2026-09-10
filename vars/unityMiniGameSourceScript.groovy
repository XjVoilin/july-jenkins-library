import org.july.release.TemplateText

/** 唯一 Source 脚本生成入口：创建 Job 和流水线更新参数使用相同文本。 */
def call(Map config) {
    def kind = config.kind?.toString()
    def project = config.projectName?.toString()
    def version = config.coreVersion?.toString()
    def root = config.buildFarmRoot?.toString()
    def platforms = config.platforms
    if (!(kind in ['hot-update', 'code-split'])) {
        throw new IllegalArgumentException('Source kind 非法')
    }
    if (!(project ==~ /[A-Za-z0-9][A-Za-z0-9_.-]*/)) {
        throw new IllegalArgumentException('Source projectName 非法')
    }
    if (kind == 'hot-update' && !(version ==~ /[0-9]+[.][0-9]+[.][0-9]+/)) {
        throw new IllegalArgumentException('Source coreVersion 非法')
    }
    if (!root || !(platforms instanceof List) || platforms.isEmpty() ||
        platforms.any { !(it in ['WeChat', 'TikTok']) }) {
        throw new IllegalArgumentException('Source 根目录或平台配置非法')
    }
    def replacements = [
        BUILD_FARM_ROOT: TemplateText.groovyString(root),
        ALLOWED_PLATFORMS: TemplateText.groovyList(platforms.collect { it.toString() }),
        SOURCE_PROJECT_NAME: TemplateText.groovyString(project),
    ]
    if (kind == 'hot-update') replacements.SOURCE_CORE_VERSION = TemplateText.groovyString(version)
    def script = libraryResource("unity-minigame/${kind}-source.groovy.tpl")
    script = script.replace('\r\n', '\n').replace('\r', '\n')
    return TemplateText.renderTemplate(script, replacements)
}
