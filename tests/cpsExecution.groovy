import com.cloudbees.groovy.cps.CpsTransformer
import com.cloudbees.groovy.cps.Continuable
import org.codehaus.groovy.control.CompilerConfiguration
import org.july.release.ReleaseStorage
def repo = new File(args[0])
def temp = File.createTempDir('release-cps-', '')
def state = new File(temp,'state.json')
ReleaseStorage.writeAtomic(state,[state:'COLLECTING'])
def config = new CompilerConfiguration()
config.sourceEncoding = 'UTF-8'
config.addCompilationCustomizers(new CpsTransformer())
def binding = new Binding([env:[BUILD_URL:'test/cps'],root:temp.path,statePath:state.path])
def shell = new GroovyShell(this.class.classLoader,binding,config)
['releaseParameters','releaseState','releaseCredentials','releaseFiles','releaseTikTok'].each { name ->
    binding.setVariable(name,shell.parse(new File(repo,'vars/'+name+'.groovy')))
}
def entry = shell.parse('''
import com.cloudbees.groovy.cps.Continuable
binding.setVariable('echo', { text -> })
binding.setVariable('error', { text -> throw new IllegalArgumentException(text.toString()) })
binding.setVariable('withEnv', { settings, body -> body() })
binding.setVariable('bat', { settings -> 'OK' })
binding.setVariable('releaseJenkinsSecrets', [withPair:{kind,first,second,body ->
    assert kind=='douyin' && first=='DY_EMAIL' && second=='DY_PASSWORD';body()
}])
def schema = releaseParameters.definitions('code-split',[platforms:['WeChat','TikTok'],sourceScript:'return []'])
def xml = releaseParameters.xml(schema)
assert xml.contains('CascadeChoiceParameter') : xml
def result = releaseState.once(statePath,'cps-upload') {
    releaseTikTok.withSession([farmRoot:root]) {
        Continuable.suspend('external-paused')
        return [receipt:'done']
    }
}
assert result.receipt=='done'
def second = releaseState.once(statePath,'cps-upload') { throw new AssertionError('duplicated external call') }
assert second.receipt=='done'
return 'finished'
''')
def flow = new Continuable(entry)
assert use(Continuable.categories) { flow.run(null) }=='external-paused'
assert ReleaseStorage.read(state).operations.'cps-upload'.status=='STARTED'
assert new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').isFile()
assert use(Continuable.categories) { flow.run(null) }=='finished'
assert ReleaseStorage.read(state).operations.'cps-upload'.status=='DONE'
assert !new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
// Same execution, interrupted while the external step is suspended.
ReleaseStorage.writeAtomic(state,[state:'COLLECTING'])
def interrupted = new Continuable(shell.parse(entry.class ? '''
import com.cloudbees.groovy.cps.Continuable
releaseState.once(statePath,'cps-interrupted') {
    releaseTikTok.withSession([farmRoot:root]) {
        Continuable.suspend('paused')
    }
}
''' : ''))
assert use(Continuable.categories) { interrupted.run(null) }=='paused'
try { use(Continuable.categories) { interrupted.runByThrow(new IOException('simulated abort')) }; assert false }
catch(java.lang.reflect.InvocationTargetException expected) { assert expected.cause instanceof IOException }
assert !new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
assert ReleaseStorage.read(state).operations.'cps-interrupted'.status=='STARTED'
// A suspended/aborted finalization can be retried in a new execution, unlike upload above.
ReleaseStorage.writeAtomic(state,[state:'COLLECTING',platform:'TikTok',collectionCycle:1])
def finalizing = new Continuable(shell.parse('''
import com.cloudbees.groovy.cps.Continuable
releaseState.finalizePackage(statePath,'finalize-1') {
    releaseTikTok.withSession([farmRoot:root]) {
        Continuable.suspend('download-paused')
        return [fingerprint:'complete']
    }
}
'''))
assert use(Continuable.categories) { finalizing.run(null) }=='download-paused'
try { use(Continuable.categories) { finalizing.runByThrow(new IOException('download aborted')) }; assert false }
catch(java.lang.reflect.InvocationTargetException expected) { assert expected.cause instanceof IOException }
assert ReleaseStorage.read(state).operations['finalize-1'].status=='STARTED'
assert !new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
binding.env.BUILD_URL='test/cps-retry'
def retrying = new Continuable(shell.parse('''
import com.cloudbees.groovy.cps.Continuable
def result = releaseState.finalizePackage(statePath,'finalize-1') {
    Continuable.suspend('retry-paused')
    return [fingerprint:'verified']
}
assert result.fingerprint=='verified'
assert releaseState.finalizePackage(statePath,'finalize-1') { assert false }.fingerprint=='verified'
return 'retried'
'''))
assert use(Continuable.categories) { retrying.run(null) }=='retry-paused'
assert ReleaseStorage.read(state).operations['finalize-1'].previousAttempts*.owner==['test/cps']
assert use(Continuable.categories) { retrying.run(null) }=='retried'
assert ReleaseStorage.read(state).operations['finalize-1'].owner=='test/cps-retry'
assert ReleaseStorage.read(state).operations['finalize-1'].status=='DONE'
println 'PASS CPS: actual CpsTransformer/Continuable; suspend/resume; module calls; abort cleanup; uncertain checkpoint retained'
