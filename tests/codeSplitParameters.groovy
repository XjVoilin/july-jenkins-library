// 直接执行生产代码中的参数校验，防止测试与实际流水线各自维护一套逻辑。
def repo = new File(args ? args[0] : new File('.').canonicalPath)
def source = new File(repo, 'vars/codeSplitPipeline.groovy').getText('UTF-8')
def stageStart = source.indexOf("stage('ValidateCredentials')")
def start = source.indexOf('def action = ', stageStart)
def end = source.indexOf('def qrActions = ', start)
assert stageStart >= 0 && start > stageStart && end > start
def validation = source.substring(start, end) + '\nreturn [action:action, platform:platform]'
def actions = ['启动代码分包采集', '再次启动代码分包采集', '查看采集状态',
               '刷新采集码', '生成正式分包并上传']
def platforms = ['WeChat', 'TikTok']
int checks = 0
def run = { params, enabled ->
    new GroovyShell(new Binding([
        params:params, config:[platforms:enabled],
        releaseParameters:new GroovyShell().parse(new File(repo,'vars/releaseParameters.groovy')),
        error:{ message -> throw new IllegalArgumentException(message.toString()) }
    ])).evaluate(validation)
}
// 复现旧逻辑：文本相同，GString 参与列表成员判断仍会失败。
def originalAction = "${actions[0]}"
def originalPlatform = "${platforms[0]}"
assert !(originalAction in actions)
assert !(originalPlatform in platforms)
checks += 2
actions.each { action ->
    platforms.each { platform ->
        [false, true].each { interpolated ->
            def params = [ACTION: interpolated ? "$action" : action,
                          PLATFORM: interpolated ? "$platform" : platform]
            def result = run(params, platforms)
            assert result.action instanceof String && result.platform instanceof String
            assert result.action == action && result.platform == platform
            checks++
        }
    }
}
[
    [params:[:], enabled:platforms, prefix:'ACTION'],
    [params:[ACTION:null, PLATFORM:'WeChat'], enabled:platforms, prefix:'ACTION'],
    [params:[ACTION:'', PLATFORM:'WeChat'], enabled:platforms, prefix:'ACTION'],
    [params:[ACTION:'非法操作', PLATFORM:'WeChat'], enabled:platforms, prefix:'ACTION'],
    [params:[ACTION:actions[0]], enabled:platforms, prefix:'PLATFORM'],
    [params:[ACTION:actions[0], PLATFORM:null], enabled:platforms, prefix:'PLATFORM'],
    [params:[ACTION:actions[0], PLATFORM:''], enabled:platforms, prefix:'PLATFORM'],
    [params:[ACTION:actions[0], PLATFORM:'Android'], enabled:platforms, prefix:'PLATFORM'],
    [params:[ACTION:actions[0], PLATFORM:'TikTok'], enabled:['WeChat'], prefix:'PLATFORM'],
    [params:[ACTION:actions[0], PLATFORM:'WeChat'], enabled:[], prefix:'PLATFORM'],
].each { scenario ->
    try {
        run(scenario.params, scenario.enabled)
        assert false : 'Invalid parameters were accepted'
    } catch (IllegalArgumentException expected) {
        assert expected.message.startsWith(scenario.prefix)
        checks++
    }
}
println "PASS: $checks checks; production validation; 5 actions x 2 platforms; String/GString; invalid/disabled inputs"
