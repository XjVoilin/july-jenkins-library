// Render production QA XML and execute its embedded script with a capture-only binding.
def repo = new File(args[0])
int checks = 0
def parameters = new GroovyShell().parse(new File(repo,'vars/releaseParameters.groovy'))
def initializer = new GroovyShell(this.class.classLoader,new Binding([
    releaseParameters:parameters,
    libraryResource:{ path -> new File(repo,'resources/'+path).getText('UTF-8') }
])).parse(new File(repo,'vars/unityMiniGameProjectJobs.groovy'))
['ProjectA','ProjectB'].each { project ->
    def platformList = project == 'ProjectA' ? ['WeChat','TikTok'] : ['TikTok']
    def xml = initializer.invokeMethod('qaJobXml',[project,"QA O'Brien & demo",
        'July.Release.Editor.BuildPipelineCI',platformList,'https://fixture.invalid/'+project,
        'D:\\Build Farm','D:\\Build Farm\\'+project+'\\Secrets\\credentials.local.json'] as Object[])
    initializer.invokeMethod('validateRenderedConfig',[xml] as Object[])
    def parsed = new XmlParser().parseText(xml)
    def defs = parsed.properties.'hudson.model.ParametersDefinitionProperty'.parameterDefinitions[0]
    assert defs.children()*.name*.text() == parameters.definitions('full-build',[platforms:platformList])*.name
    assert !defs.children()*.name*.text().any { it in ['CORE_VERSION','PLAN_VERSION','SOURCE','BRANCH'] }
    assert parsed.definition.sandbox.text() == 'true'
    assert parsed.disabled.text() == 'false'
    def captured
    def script = parsed.definition.script.text().replace("@Library('unity-minigame') _",'')
    new GroovyShell(new Binding([unityMiniGamePipeline:{ Map cfg -> captured=cfg }])).evaluate(script)
    assert captured.coreVersion == '99.99.99' && captured.buildType == 'FullBuild'
    assert captured.projectName == project && captured.platforms == platformList
    assert captured.repoUrl == 'https://fixture.invalid/'+project
    assert captured.credentialsFile == 'D:\\Build Farm\\'+project+'\\Secrets\\credentials.local.json'
    assert !captured.containsKey('extraArgs') && !captured.containsKey('fixedParams')
    checks += 9
}
def versionJobs = new GroovyShell(this.class.classLoader).parse(new File(repo,'vars/unityMiniGameVersionJobs.groovy'))
try {
    versionJobs.invokeMethod('validateConfig',['ProjectA','99.99.99','Example',['WeChat']] as Object[])
    assert false : 'Reserved version must be rejected'
} catch (IllegalArgumentException expected) { assert expected.message.contains('QA'); checks++ }
versionJobs.invokeMethod('validateConfig',['ProjectA','1.7.0','Example',['WeChat']] as Object[])
checks++
try {
    initializer.invokeMethod('validateConfig',['ProjectA','Display','https://fixture.invalid','99.99.99',
        'Example',['WeChat'],'D:\\Build Farm','unity'] as Object[])
    assert false : 'Reserved initial version must be rejected'
} catch (IllegalArgumentException expected) { assert expected.message.contains('QA'); checks++ }
println "PASS project jobs: $checks checks; QA fixed version, shared parameters, XML/Groovy escaping, project isolation, reserved version"
