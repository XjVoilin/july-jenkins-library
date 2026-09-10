// 管理员脚本控制台只读验收；不更新 Job、不审批脚本、不触发构建。
import jenkins.model.Jenkins
import hudson.model.ParametersDefinitionProperty
def cfg=binding.hasVariable('verification') ? verification : [:]
def project=cfg.projectName?.toString()
def version=cfg.coreVersion?.toString()
assert project ==~ /[A-Za-z0-9][A-Za-z0-9_.-]*/
assert version ==~ /\d+\.\d+\.\d+/
def j=Jenkins.get()
j.checkPermission(Jenkins.ADMINISTER)
def jobs=[project+'/'+version+'_HotUpdate',project+'/CodeSplit']
jobs.each { name ->
    def job=j.getItemByFullName(name)
    assert job : 'Job missing: '+name
    def xml=new XmlParser().parseText(job.configFile.asString())
    assert xml.properties.'hudson.model.ParametersDefinitionProperty'.size()==1 : 'Duplicate parameter properties'
    def p=job.getProperty(ParametersDefinitionProperty).getParameterDefinition('SOURCE')
    assert p && !p.script.script.script.contains('jenkinsProject') : 'Stale Source script'
    def cases=name.endsWith('/CodeSplit') ? [[:]]+['WeChat','TikTok'].collectMany { platform ->
        ['启动代码分包采集','再次启动代码分包采集','查看采集状态','刷新采集码','生成正式分包并上传'].collect {
            [ACTION:it,PLATFORM:platform]
        }
    } : [[:]]
    cases.each { values ->
        def choices=p.getChoices(values).values().collect { it.toString() }
        assert choices && !choices.any { it.contains('读取失败') || it.contains('配置错误') } : choices
    }
    println 'VERIFIED: '+name
}
println 'PASS: live Source choices without Job-page context. No build/upload was triggered.'
