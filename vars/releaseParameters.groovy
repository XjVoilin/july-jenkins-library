/** 参数规则只维护一份：Job XML 和运行时 properties 使用相同定义。 */
def definitions(String mode, Map config) {
    def source = [type:'activeChoice',name:'SOURCE',persist:false,
        desc:'选择当前项目的全量 Source；平台、环境、Debug 与 AOT 绑定该基线',
        randomName:mode == 'code-split' ? 'codesplit-source-choice' : 'hotupdate-source-choice',
        script:config.sourceScript]
    if (mode == 'code-split') {
        source.referencedParameters = 'ACTION,PLATFORM'
        return [
            [type:'choice',name:'ACTION',choices:actions(),desc:'选择采集、查询或正式分包操作'],
            [type:'choice',name:'PLATFORM',choices:config.platforms,desc:'目标平台；Source 随操作和平台刷新'],
            source
        ]
    }
    def common = [
        [type:'bool',name:'CLEAN_LIBRARY',persist:false,defaultValue:false,desc:'清理 Library 缓存'],
        [type:'bool',name:'FORCE_REBUILD',persist:false,defaultValue:false,
         desc:'允许同版本重建和覆盖上传；不允许覆盖已存在的 Source 快照'],
    ]
    if (mode == 'hot-update') {
        return [source,[type:'string',name:'PLAN_VERSION',persist:false,defaultValue:config.coreVersion,
            desc:'热更目标版本，不能小于核心版本']] + common
    }
    if (mode != 'full-build') error "非法参数模式: $mode"
    return [
        [type:'choice',name:'PLATFORM',choices:config.platforms,desc:'目标平台'],
        [type:'choice',name:'ENV',choices:['Dev','Test','Prod'],desc:'构建环境'],
        [type:'bool',name:'DEBUG',defaultValue:false,desc:'开启 JULYGF_DEBUG'],
    ] + common + [[type:'bool',name:'BUILD_MINIGAME',defaultValue:true,desc:'全量构建生成并上传小游戏包']]
}
def actions() {
    ['启动代码分包采集','再次启动代码分包采集','查看采集状态','刷新采集码','生成正式分包并上传']
}
def value(String name, List defs, Map supplied, Map fixed = [:]) {
    def cfg = defs.find { it.name == name }
    def result = fixed.containsKey(name) ? fixed[name] :
        (supplied.containsKey(name) ? supplied[name] :
            (cfg?.type == 'choice' ? cfg.choices[0] : cfg?.defaultValue))
    if (result == null) return null
    if (cfg?.type == 'bool') {
        if (result instanceof Boolean) return result
        def text = result.toString()
        if (!(text in ['true','false'])) error "布尔参数非法: $name"
        return text.toBoolean()
    }
    if (result instanceof CharSequence) result = result.toString()
    if (cfg?.type == 'choice' && !(result in cfg.choices)) error "枚举参数非法: $name=$result"
    result
}
def descriptor(Map cfg, Map saved = [:]) {
    def base = [name:cfg.name,description:cfg.desc]
    switch (cfg.type) {
        case 'activeChoice':
            def result = base + [
                '$class':cfg.referencedParameters ? 'CascadeChoiceParameter' : 'ChoiceParameter',
                choiceType:'PT_SINGLE_SELECT',filterable:true,filterLength:1,randomName:cfg.randomName,
                script:['$class':'GroovyScript',
                    script:[classpath:[],sandbox:false,script:cfg.script],
                    fallbackScript:[classpath:[],sandbox:false,
                        script:"return ['SOURCE 读取失败：请检查 Active Choices 脚本审批和状态文件']"]]]
            if (cfg.referencedParameters) result.referencedParameters = cfg.referencedParameters
            return result
        case 'choice':
            def choices = new ArrayList(cfg.choices)
            def last = cfg.persist == false ? null : saved[cfg.name]
            if (last && last in choices) { choices.remove(last); choices.add(0,last) }
            return base + ['$class':'ChoiceParameterDefinition',choices:choices]
        case 'string':
            return base + ['$class':'StringParameterDefinition',trim:true,
                defaultValue:cfg.persist != false && saved.containsKey(cfg.name) ? saved[cfg.name] : (cfg.defaultValue ?: '')]
        case 'bool':
            return base + ['$class':'BooleanParameterDefinition',
                defaultValue:cfg.persist != false && saved.containsKey(cfg.name) ?
                    saved[cfg.name].toString().toBoolean() : cfg.defaultValue]
        default: error "参数类型非法: $cfg.type"
    }
}
def sync(List defs, String savedFile = null) {
    def saved = savedFile && fileExists(savedFile) ? readProperties(file:savedFile) : [:]
    properties([disableConcurrentBuilds(), parameters(defs.collect { descriptor(it, saved) })])
}
def save(List defs, String savedFile, Closure get) {
    writeFile file:savedFile, text:defs.findAll { it.persist != false }.collect {
        def v = get(it.name)
        it.name + '=' + (v == null ? '' : v.toString())
    }.join('\n')
}
def xml(List defs) {
    defs.collect { cfg ->
        def d = descriptor(cfg)
        def tag = cfg.type == 'activeChoice' ? 'org.biouno.unochoice.'+d['$class'] : 'hudson.model.'+d['$class']
        def body = '<name>'+escape(d.name)+'</name><description>'+escape(d.description)+'</description>'
        if (cfg.type == 'activeChoice') {
            body += '<randomName>'+escape(d.randomName)+'</randomName><visibleItemCount>1</visibleItemCount>'
            body += '<script class="org.biouno.unochoice.model.GroovyScript">'
            ['secureScript':d.script.script.script,'secureFallbackScript':d.script.fallbackScript.script].each { name, script ->
                body += '<'+name+'><script>'+escape(script)+'</script><sandbox>false</sandbox><classpath/></'+name+'>'
            }
            body += '</script><choiceType>PT_SINGLE_SELECT</choiceType><filterable>true</filterable><filterLength>1</filterLength>'
            if (d.referencedParameters) body += '<parameters class="linked-hash-map"/><referencedParameters>'+escape(d.referencedParameters)+'</referencedParameters>'
        } else if (cfg.type == 'choice') {
            body += '<choices>'+d.choices.collect { '<string>'+escape(it)+'</string>' }.join('')+'</choices>'
        } else {
            body += '<defaultValue>'+escape(d.defaultValue)+'</defaultValue>'
            if (cfg.type == 'string') body += '<trim>true</trim>'
        }
        '<'+tag+'>'+body+'</'+tag+'>'
    }.join('\n')
}
private String escape(Object value) {
    (value == null ? '' : value.toString()).replace('&','&amp;').replace('<','&lt;')
        .replace('>','&gt;').replace('"','&quot;').replace("'",'&apos;')
}
