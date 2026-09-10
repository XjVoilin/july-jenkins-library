import org.july.release.TemplateText
def repo = new File(args[0])
def binding = new Binding([error:{ m -> throw new IllegalArgumentException(m.toString()) }])
def parameters = new GroovyShell(binding).parse(new File(repo,'vars/releaseParameters.groovy'))
int checks=0
def check={boolean b -> assert b; checks++}
['full-build','hot-update','code-split'].each { mode ->
    def defs=parameters.definitions(mode,[platforms:['WeChat','TikTok'],coreVersion:'1.6.0',sourceScript:'return ["A&B"]'])
    def body=parameters.xml(defs)
    def replacements=[PARAMETER_DEFINITIONS_XML:body,DESCRIPTION_XML:'Test &amp; Name',PIPELINE_SCRIPT_XML:'println("test")']
    def xml=TemplateText.renderTemplate(new File(repo,'resources/unity-minigame/pipeline-job.xml.tpl').getText('UTF-8'),replacements)
    def root=new XmlParser().parseText(xml)
    def nodes=root.properties.'hudson.model.ParametersDefinitionProperty'.parameterDefinitions[0].children()
    check(nodes.size()==defs.size())
    defs.eachWithIndex { cfg,i ->
        def descriptor=parameters.descriptor(cfg)
        def node=nodes[i]
        check(node.name.text()==descriptor.name)
        check(node.description.text()==descriptor.description)
        if(cfg.type=='choice') check(node.choices.string*.text()==descriptor.choices)
        if(cfg.type=='bool') check(node.defaultValue.text()==descriptor.defaultValue.toString())
        if(cfg.type=='activeChoice') {
            check(node.script.secureScript.script.text()==descriptor.script.script.script)
            check(node.script.secureFallbackScript.script.text()==descriptor.script.fallbackScript.script)
            check(node.referencedParameters.text()==(descriptor.referencedParameters ?: ''))
        }
    }
    check(!xml.contains('@@'))
}
def full=parameters.definitions('full-build',[platforms:['WeChat','TikTok']])
check(parameters.value('DEBUG',full,[DEBUG:false],[DEBUG:true])==true)
check(parameters.value('PLATFORM',full,[:])=='WeChat')
check(parameters.value('DEBUG',full,[DEBUG:'false'])==false)
try { parameters.value('DEBUG',full,[DEBUG:'invalid']);assert false }
catch(IllegalArgumentException expected){checks++}
try { parameters.value('PLATFORM',full,[PLATFORM:'Android']);assert false }
catch(IllegalArgumentException expected){checks++}
check(parameters.descriptor(full.find{it.name=='FORCE_REBUILD'},[FORCE_REBUILD:'true']).defaultValue==false)
println "PASS schema: $checks checks; XML/runtime parity, defaults, locked values, strict types"
