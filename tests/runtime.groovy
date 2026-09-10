import org.july.release.ReleaseStorage as Store
import java.util.concurrent.*
def repo = new File(args[0])
def temp = File.createTempDir('release-runtime-', '')
int checks = 0
def check = { boolean ok -> assert ok; checks++ }
def rejects = { Closure action ->
    try { action(); assert false : 'Expected rejection' }
    catch (IllegalArgumentException | IllegalStateException expected) { checks++ }
}
def root = new File(temp,'ProjectA')
def lease = Store.acquire(root,'Job/FullBuild#1')
rejects { Store.acquire(root,'Job/HotUpdate#1') }
rejects { Store.release(lease + [token:'foreign']) }
check(new File(lease.path).exists())
def other = Store.acquire(new File(temp,'ProjectB'),'Other/FullBuild#1')
Store.release(other)
Store.release(lease)
check(!new File(lease.path).exists())
def again = Store.acquire(root,'Job/CodeSplit#2')
Store.release(again)
rejects { Store.inside(root,root) }
rejects { Store.inside(root,new File(root,'../ProjectB')) }
def gate = new CountDownLatch(1)
def pool = Executors.newFixedThreadPool(2)
def attempts = (1..2).collect { n ->
    pool.submit({ -> gate.await(); try { return Store.acquire(root,'Race'+n) }
                     catch(IllegalStateException expected) { return null } } as Callable)
}
gate.countDown()
def winners = attempts.collect { it.get(10,TimeUnit.SECONDS) }.findAll { it }
check(winners.size()==1)
Store.release(winners[0]); pool.shutdown()
def sources = new File(root,'ReleaseSources')
def source = new File(Store.reserve(sources,'WeChat','1.6.0','1'))
rejects { Store.reserve(sources,'WeChat','1.6.0','1') }
check(new File(Store.reserve(sources,'TikTok','1.6.0','1')).isDirectory())
rejects { Store.reserve(sources,'../Other','1.6.0','2') }
rejects { Store.reserve(sources,'WeChat','../1.6.0','2') }
rejects { Store.reserve(sources,'WeChat','1.6.0','../2') }
def aot=new File(Store.aotPath(source))
check(!aot.exists())
rejects { Store.aotPath(source,new File(temp,'foreign/aot').path) }
rejects { Store.aotPath(source,aot.path,true) }
aot.mkdirs()
rejects { Store.aotPath(source,aot.path,true) }
new File(aot,'empty-subdir').mkdirs()
rejects { Store.aotPath(source,aot.path,true) }
new File(aot,'framework-owned.fixture').text='opaque'
check(Store.aotPath(source,aot.path,true)==aot.canonicalPath)
def stateFile = new File(source,'code-split-state.json')
Store.writeAtomic(stateFile,[state:'SNAPSHOTTED',projectName:'ProjectA',platform:'WeChat',
    version:'1.6.0',sourceBuildNumber:1,debug:false,buildEnvironment:'Dev',rawWasmMd5:'same'])
rejects { Store.update(stateFile,[state:'UPLOADED']) }
rejects { Store.update(stateFile,[projectName:'ProjectB']) }
rejects { Store.update(stateFile,[buildEnvironment:'Prod']) }
rejects { Store.update(stateFile,[debug:true]) }
rejects { Store.update(stateFile,[schemaVersion:6]) }
rejects { Store.update(stateFile,[buildTarget:'MiniGame']) }
Store.update(stateFile,[state:'SOURCE_READY'])
Store.update(stateFile,[state:'COLLECTING',collectionCycle:1])
Store.update(stateFile,[state:'UPLOADED'])
Store.update(stateFile,[state:'COLLECTING',collectionCycle:2])
check(Store.read(stateFile).collectionCycle==2)
def script = new GroovyShell(new Binding([env:[BUILD_URL:'test/1'],echo:{ message -> }]))
    .parse(new File(repo,'vars/releaseState.groovy'))
int finalized=0, uploaded=0
def finalize = { script.once(stateFile.path,'finalize-2') { finalized++; [fingerprint:'stable'] } }
def upload = { script.once(stateFile.path,'upload-2') { uploaded++; [receipt:'accepted'] } }
check(finalize().fingerprint=='stable')
check(upload().receipt=='accepted')
// Simulated reference-save failure after upload: rerun must not repeat either external operation.
check(finalize().fingerprint=='stable')
check(upload().receipt=='accepted')
check(finalized==1 && uploaded==1)
try { script.once(stateFile.path,'uncertain') { throw new IOException('remote outcome unknown') }; assert false }
catch(IOException expected) { checks++ }
int attemptedAgain=0
rejects { script.once(stateFile.path,'uncertain') { attemptedAgain++ } }
check(attemptedAgain==0)
check(Store.read(stateFile).operations.uncertain.status=='STARTED')
check(source.listFiles().every { !it.name.startsWith('.release-') })
['WeChat','TikTok'].each { platform ->
    def retryFile = new File(temp,platform+'-retry.json')
    def base = [state:'COLLECTING',platform:platform,collectionCycle:1,rawWasmMd5:'baseline',
                operations:['collect-1':[status:'DONE',owner:'collect/1']]]
    Store.writeAtomic(retryFile,base)
    int runs = 0
    (1..2).each { attempt ->
        script.binding.env.BUILD_URL = 'finalize/'+attempt
        try {
            script.finalizePackage(retryFile.path,'finalize-1') {
                runs++; throw new IOException('download interrupted')
            }
            assert false
        } catch(IOException expected) { checks++ }
    }
    script.binding.env.BUILD_URL = 'finalize/3'
    check(script.finalizePackage(retryFile.path,'finalize-1') { runs++; [fingerprint:'verified'] }.fingerprint=='verified')
    check(script.finalizePackage(retryFile.path,'finalize-1') { assert false }.fingerprint=='verified')
    def saved = Store.read(retryFile)
    check(runs==3 && saved.operations['finalize-1'].owner=='finalize/3')
    check(saved.operations['finalize-1'].previousAttempts*.owner==['finalize/1','finalize/2'])
    check(saved.operations['finalize-1'].previousAttempts.every { it.status=='STARTED' && it.supersededAt && !it.previousAttempts })
    check(saved.operations['collect-1']==base.operations['collect-1'] && saved.rawWasmMd5=='baseline')
    // An earlier successful upload must not prevent a later collection cycle from finalizing.
    Store.update(retryFile,[collectionCycle:2,operations:saved.operations+['upload-1':[status:'DONE']]])
    check(script.finalizePackage(retryFile.path,'finalize-2') { [ok:true] }.ok)
    // Retry policy cannot be applied to collection/upload, a different cycle or invalid state/platform.
    ['collect-2','upload-2','finalize-1','finalize-3'].each { key ->
        rejects { script.finalizePackage(retryFile.path,key) { assert false } }
    }
    [[state:'UPLOADED'],[platform:'Unknown'],[collectionCycle:0]].each { change ->
        Store.writeAtomic(retryFile,base+change)
        rejects { script.finalizePackage(retryFile.path,'finalize-1') { assert false } }
    }
    ['STARTED','DONE'].each { uploadStatus ->
        Store.writeAtomic(retryFile,base+[operations:[
            'finalize-1':[status:'STARTED',owner:'old'], 'upload-1':[status:uploadStatus]]])
        def before=retryFile.text
        rejects { script.finalizePackage(retryFile.path,'finalize-1') { assert false } }
        check(retryFile.text==before)
    }
    ['collect-1','finalize-2','upload-2'].each { otherKey ->
        Store.writeAtomic(retryFile,base+[operations:[
            'finalize-1':[status:'STARTED',owner:'old'], (otherKey):[status:'STARTED']]])
        def before=retryFile.text
        rejects { script.finalizePackage(retryFile.path,'finalize-1') { assert false } }
        check(retryFile.text==before)
    }
    Store.writeAtomic(retryFile,base+[operations:['finalize-1':[status:'invalid']]])
    rejects { script.finalizePackage(retryFile.path,'finalize-1') { assert false } }
}
println "PASS runtime: $checks checks; leases/race/ownership; source collisions; immutable state; checkpoints"
println "Fixtures: $temp"
