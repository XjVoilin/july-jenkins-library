// Actual local Git repositories only; no network, Jenkins jobs, Unity or platform uploads.
import com.cloudbees.groovy.cps.CpsTransformer
import org.codehaus.groovy.control.CompilerConfiguration
import groovy.json.JsonOutput
def repo=new File(args[0])
def temp=File.createTempDir('release-git contract ', '')
def origin=new File(temp,'origin');def work=new File(temp,'work');work.mkdirs()
def run={ List command,File directory ->
    def process=new ProcessBuilder(command).directory(directory).redirectErrorStream(true).start()
    def output=process.inputStream.getText('UTF-8')
    int status=process.waitFor()
    if(status!=0) throw new IOException(output)
    output.trim()
}
run(['git','init','--bare',origin.path],temp)
run(['git','init'],work)
run(['git','config','user.name','Release Test'],work)
run(['git','config','user.email','release-test@example.invalid'],work)
new File(work,'fixture').text='fixture'
run(['git','add','fixture'],work);run(['git','commit','-m','fixture'],work)
run(['git','remote','add','origin',origin.path],work)
def commands=[];boolean failPush=true
def binding=new Binding([
    error:{s->throw new IllegalStateException(s.toString())},echo:{s->},
    writeFile:{m->new File(work,m.file).setText(m.text.toString(),'UTF-8')},
    bat:{arg->
        def command=(arg instanceof Map?arg.script:arg.toString()).toString()
        commands<<command
        if(failPush && command.contains('git push')) throw new IOException('simulated push failure')
        run(['cmd.exe','/d','/c',command],work)
    }])
def module=new GroovyShell(this.class.classLoader,binding).parse(new File(repo,'vars/releaseGitTag.groovy'))
def tag=module.plan([platform:'WeChat',environment:'Dev',coreVersion:'1.6.0',planVersion:'1.6.0',
    buildType:'FullBuild',buildNumber:'1',buildUrl:'test/1',cdnUrl:'https://cdn.example/Project'])
try {module.publish(tag);assert false} catch(IOException expected) {assert expected.message.contains('push failure')}
int creates=commands.count {it.contains('git tag -a')}
failPush=false;module.publish(tag);module.publish(tag)
assert commands.count {it.contains('git tag -a')}==creates
assert run(['git','rev-parse',tag.name+'^{commit}'],origin)==tag.commit
try {module.publish(tag+[commit:'0'*40]);assert false} catch(IllegalStateException expected) {assert expected.message.contains('禁止覆盖')}
def next=module.plan([platform:'WeChat',environment:'Dev',coreVersion:'1.6.0',planVersion:'1.6.0',
    buildType:'FullBuild',buildNumber:'2',buildUrl:'test/2',cdnUrl:'https://cdn.example/Project'])
assert next.name.endsWith('+full-b2')

// Strict result contract: successful exit or a directory alone is insufficient.
def aot=new File(temp,'aot');aot.mkdirs()
def packageDir=new File(temp,'custom package');packageDir.mkdirs();new File(packageDir,'game.js').text='fixture'
def expected=[buildType:'FullBuild',platform:'WeChat',environment:'Dev',coreVersion:'1.6.0',planVersion:'1.6.0',
    debug:false,player:true,aotBackupPath:aot.path]
def report=expected+[schemaVersion:1,succeeded:true,cdnUploaded:true,buildTarget:'MiniGame',packageDirectory:packageDir.path]
def original=new LinkedHashMap(report)
binding.setVariable('readJSON',{m->report})
def results=new GroovyShell(this.class.classLoader,binding).parse(new File(repo,'vars/releaseUnityResult.groovy'))
assert results.read(expected).packageDirectory==packageDir.path
[[schemaVersion:0],[succeeded:false],[cdnUploaded:false],[platform:'TikTok'],[coreVersion:'2.0.0'],
 [debug:true],[environment:'Prod'],[buildTarget:'WebGL'],[packageDirectory:temp.path],
 [aotBackupPath:work.path]].each {change->
    report=original+change
    try {results.read(expected);assert false:change} catch(IllegalStateException correct) {}
}

// Compile modified entry points with the real CPS transformer as well as executing ordinary Groovy above.
def config=new CompilerConfiguration();config.addCompilationCustomizers(new CpsTransformer())
def cps=new GroovyShell(this.class.classLoader,new Binding(),config)
['unityMiniGamePipeline','releaseUnityResult','releaseGitTag'].each { name->cps.parse(new File(repo,'vars/'+name+'.groovy')) }
def cpsBinding=new Binding([tag:tag])
def runtime=new GroovyShell(this.class.classLoader,cpsBinding,config)
cpsBinding.setVariable('releaseGitTag',runtime.parse(new File(repo,'vars/releaseGitTag.groovy')))
def entry=runtime.parse("""
    binding.setVariable('echo',{ message -> })
    binding.setVariable('error',{ message -> throw new IllegalStateException(message.toString()) })
    binding.setVariable('bat',{ settings ->
        assert settings.script.contains('git ls-remote')
        com.cloudbees.groovy.cps.Continuable.suspend('remote-query')
        return tag.commit + ' refs/tags/' + tag.name + '^{}'
    })
    releaseGitTag.publish(tag)
    return 'tag-confirmed'
""")
def flow=new com.cloudbees.groovy.cps.Continuable(entry)
assert flow.run(null)=='remote-query'
assert flow.run(null)=='tag-confirmed'
println 'PASS releaseBuildInterface: local Git retry/idempotency/conflict, strict result contract, CPS compilation'
assert temp.deleteDir()
