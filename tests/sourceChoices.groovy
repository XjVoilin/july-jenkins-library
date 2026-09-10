import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases

def repo = new File(args ? args[0] : new File('.').canonicalPath)
def renderer = new GroovyShell(new Binding([
    libraryResource: { path -> new File(repo, 'resources/' + path).getText('UTF-8') }
])).parse(new File(repo, 'vars/unityMiniGameSourceScript.groovy'))
def render = { kind, project, version, root ->
    renderer.call([kind: kind, projectName: project, coreVersion: version,
        buildFarmRoot: root, platforms: ['WeChat', 'TikTok']])
}
int checks = 0
def verify = { boolean condition -> assert condition; checks++ }
def evaluate = { text, binding = [:] -> new GroovyShell(new Binding(binding)).evaluate(text) }
def temp = File.createTempDir('jenkins-source-regression-', '')
def actualHot = render('hot-update', 'ProjectA', '1.6.0', temp.path)
def actualSplit = render('code-split', 'ProjectA', null, temp.path)
verify(!actualHot.contains('jenkinsProject') && !actualSplit.contains('jenkinsProject'))
verify(evaluate(actualSplit) == ['没有可用原包：请先选择有效平台'])
verify(evaluate(actualSplit, [PLATFORM:'WeChat']) == ['没有可用原包：请选择有效操作'])
verify(evaluate(actualSplit, [ACTION:'wrong',PLATFORM:'WeChat']) == ['没有可用原包：请选择有效操作'])
verify(evaluate(actualSplit, [ACTION:'启动代码分包采集',PLATFORM:'wrong']) == ['没有可用原包：请先选择有效平台'])
int number = 0
['ProjectA','ProjectB'].each { project ->
    ['WeChat','TikTok'].each { platform ->
        ['1.6.0','1.7.0'].each { version ->
            ['SOURCE_READY','COLLECTING','UPLOADED'].each { state ->
                ['Dev','Prod'].each { environment ->
                    def dir = new File(temp, "$project/ReleaseSources/$platform/$version/" + (++number))
                    def aot = new File(dir, 'aot')
                    assert aot.mkdirs()
                    new File(dir,'code-split-state.json').setText(JsonOutput.toJson([
                        schemaVersion:6,buildTarget:'MiniGame',
                        projectName:project,platform:platform,version:version,state:state,
                        sourceBuildNumber:number,buildEnvironment:environment,debug:false,
                        aotBackupPath:aot.path,aotBackupFingerprint:'fixture'
                    ]), 'UTF-8')
                }
            }
        }
    }
}
def actions = ['启动代码分包采集':['SOURCE_READY'],'再次启动代码分包采集':['UPLOADED'],
    '查看采集状态':['COLLECTING','UPLOADED'],'刷新采集码':['COLLECTING'],'生成正式分包并上传':['COLLECTING']]
['ProjectA','ProjectB'].each { project ->
    def hot = render('hot-update',project,'1.6.0',temp.path)
    def split = render('code-split',project,null,temp.path)
    def result = evaluate(hot, [jenkinsProject:[name:'WRONG',parent:[name:'WRONG']]])
    verify(result.size() == 13)
    verify(result.drop(1).every { it.split(/\|/)[1] == '1.6.0' })
    def ownRoot = new File(temp, "$project/ReleaseSources")
    result.drop(1).each { row ->
        def parts = row.split(/\|/)
        verify(new File(ownRoot, parts[0]+'/'+parts[1]+'/'+parts[2]).isDirectory())
    }
    ['WeChat','TikTok'].each { platform ->
        actions.each { action, allowed ->
            def found = evaluate(split,[ACTION:action,PLATFORM:platform])
            verify(found.size() == allowed.size()*4)
            verify(found.every { def p=it.split(/\|/); p[0]==platform && p[3] in allowed })
            verify(found.every { def p=it.split(/\|/); new File(ownRoot,p[0]+'/'+p[1]+'/'+p[2]).isDirectory() })
        }
    }
}
// 旧协议或指向其他 Source 的状态，不能重新进入热更选择列表。
def candidate=new File(temp,'ProjectA/ReleaseSources/WeChat/1.6.0/1/code-split-state.json')
def savedText=candidate.getText('UTF-8')
def savedState=new groovy.json.JsonSlurperClassic().parseText(savedText)
[
    [schemaVersion:5],
    [buildTarget:'StandaloneWindows64'],
    [sourceBuildNumber:2],
    [aotBackupPath:new File(temp,'ProjectB/ReleaseSources/WeChat/1.6.0/25/aot').path]
].each { change ->
    candidate.setText(JsonOutput.toJson(savedState+change),'UTF-8')
    try {
        def choices=evaluate(actualHot)
        verify(!choices.any { it.startsWith('WeChat|1.6.0|1|') })
    } finally { candidate.setText(savedText,'UTF-8') }
}
verify(evaluate(render('hot-update','Missing','1.6.0',temp.path)).size()==1)
verify(evaluate(render('hot-update','ProjectA','9.9.9',temp.path)).size()==1)
['../ProjectB', '', null].each { bad ->
    try { render('hot-update',bad,'1.6.0',temp.path); assert false }
    catch (IllegalArgumentException expected) { checks++ }
}
try { render('hot-update','ProjectA','../1.6.0',temp.path); assert false }
catch (IllegalArgumentException expected) { checks++ }
def crlfRenderer = new GroovyShell(new Binding([
    libraryResource: { path -> new File(repo,'resources/'+path).getText('UTF-8').replace('\r\n','\n').replace('\n','\r\n') }
])).parse(new File(repo,'vars/unityMiniGameSourceScript.groovy'))
verify(actualHot == crlfRenderer.call([kind:'hot-update',projectName:'ProjectA',coreVersion:'1.6.0',
    buildFarmRoot:temp.path,platforms:['WeChat','TikTok']]))
// 渲染器可复用共享库工具，但生成的 Active Choices 脚本必须保持独立。
verify(!actualHot.contains('org.july.release') && !actualSplit.contains('org.july.release'))
verify(actualSplit == crlfRenderer.call([kind:'code-split',projectName:'ProjectA',
    buildFarmRoot:temp.path,platforms:['WeChat','TikTok']]))
def literalRenderer = new GroovyShell(new Binding([
    libraryResource: { path ->
        '[root:@@BUILD_FARM_ROOT@@, platforms:@@ALLOWED_PLATFORMS@@, project:@@SOURCE_PROJECT_NAME@@]'
    }
])).parse(new File(repo,'vars/unityMiniGameSourceScript.groovy'))
def specialRoot = "D:\\Build Farm\\O'Brien\r\nfixture"
def literalResult = evaluate(literalRenderer.call([kind:'code-split',projectName:'ProjectA',
    buildFarmRoot:specialRoot,platforms:['WeChat', "TikTok"]]))
verify(literalResult == [root:specialRoot,platforms:['WeChat','TikTok'],project:'ProjectA'])
def unresolvedRenderer = new GroovyShell(new Binding([
    libraryResource: { path -> '@@UNKNOWN_PLACEHOLDER@@' }
])).parse(new File(repo,'vars/unityMiniGameSourceScript.groovy'))
try {
    unresolvedRenderer.call([kind:'code-split',projectName:'ProjectA',
        buildFarmRoot:temp.path,platforms:['WeChat']])
    assert false
} catch (IllegalStateException expected) { checks++ }
['unityMiniGamePipeline','codeSplitPipeline','unityMiniGameVersionJobs','unityMiniGameProjectJobs'].each { name ->
    def text = new File(repo,'vars/' + name + '.groovy').getText('UTF-8')
    verify(text.contains('unityMiniGameSourceScript('))
    verify(!text.contains('jenkinsProject'))
    def unit = new CompilationUnit()
    unit.addSource(name + '.groovy', text)
    unit.compile(Phases.CONVERSION)
    checks++
}
println "PASS: $checks checks (offline; no Jenkins build, upload or collection invoked)"
println "Fixture directory: $temp"
