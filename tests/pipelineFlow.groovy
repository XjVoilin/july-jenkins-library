// 执行真实流水线脚本及状态代码，替换 Jenkins DSL、Unity 和平台服务。
// 本测试不证明真实 Unity/平台 CLI 成功；它验证阶段顺序、隔离、异常和重试。
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import net.sf.json.JSONSerializer
import org.july.release.ReleaseStorage
import java.security.MessageDigest

def repo = new File(args[0])
// Ensure every production readJSON opts into the JSON representation accepted by state persistence.
new File(repo,'vars').eachFileMatch(~/.*[.]groovy/) { file ->
    file.eachLine('UTF-8') { line ->
        if(line =~ /\breadJSON\s+file:/) assert line.contains('returnPojo: true') : file.name+': '+line
    }
}
def farm = File.createTempDir('release-flow with spaces-', '')
def harness = new FlowHarness(repo:repo,farm:farm)
['WeChat','TikTok'].each { platform ->
    ['1','2'].each { number ->
        def qa = harness.runBuild('QaProject',platform,number,false,null,null,
            [coreVersion:'99.99.99',params:[PLAN_VERSION:'1.2.3']])
        assert !qa.failure : qa.failure
        assert qa.commands.any { it.contains('git checkout -B Tuanjie_Build/99.99.99 origin/Tuanjie_Build/99.99.99') }
        assert qa.commands.any { it.contains('-executeMethod Example.FullBuild') && it.contains('-planVersion 99.99.99') }
        assert new File(qa.outputAot).canonicalFile == new File(farm,
            'QaProject/ReleaseSources/'+platform+'/99.99.99/'+number+'/aot').canonicalFile
        def state = ReleaseStorage.read(new File(new File(qa.outputAot).parentFile,'code-split-state.json'))
        assert state.version == '99.99.99' && state.state == 'SOURCE_READY'
    }
}

def wx = harness.runBuild('ProjectA','WeChat','1')
assert !wx.failure : wx.failure
def wxSource = new File(farm,'ProjectA/ReleaseSources/WeChat/1.6.0/1/code-split-state.json')
assert ReleaseStorage.read(wxSource).state=='SOURCE_READY'
assert ReleaseStorage.read(wxSource).schemaVersion==6 && ReleaseStorage.read(wxSource).buildTarget=='MiniGame'
assert new File(wx.outputAot).canonicalFile==new File(wxSource.parentFile,'aot').canonicalFile
// A failure after the upload receipt must be completable without rebuilding or uploading again.
def pending = harness.runBuild('FinishOnly','WeChat','1',false,null,'CompletePublication')
assert pending.failure
def pendingState = new File(farm,'FinishOnly/ReleaseSources/WeChat/1.6.0/1/code-split-state.json')
assert ReleaseStorage.read(pendingState).state == 'SNAPSHOTTED'
assert ReleaseStorage.read(pendingState).operations['full-upload'].status == 'DONE'
int uploadsBeforeCompletion = harness.uploads
int commandsBeforeCompletion = pending.commands.size()
pending.completeFull(pendingState.path)
assert ReleaseStorage.read(pendingState).state == 'SOURCE_READY'
assert harness.uploads == uploadsBeforeCompletion
assert !pending.commands.drop(commandsBeforeCompletion).any { it.contains('fixture-unity') }
pending.completeFull(pendingState.path)
assert harness.uploads == uploadsBeforeCompletion
assert ReleaseStorage.read(pendingState).releaseUploadedByBuild == 1

def tt = harness.runBuild('ProjectA','TikTok','2')
assert !tt.failure : tt.failure
def b = harness.runBuild('ProjectB','WeChat','1')
assert !b.failure : b.failure
assert new File(farm,'ProjectB/ReleaseSources/WeChat/1.6.0/1/code-split-state.json').isFile()
def collision = harness.runBuild('ProjectA','WeChat','1')
assert collision.failure?.message?.contains('Source')
assert !collision.executed.contains('Build')
def hot = harness.runBuild('ProjectA','WeChat','3',true,'WeChat|1.6.0|1|SOURCE_READY')
assert !hot.failure : hot.failure
assert hot.env.HOTUPDATE_SOURCE_BUILD=='1'
assert !hot.executed.contains('SnapshotReleaseSource')
assert !hot.executed.contains('RestoreSourceAOT')
assert new File(hot.inputAot).canonicalFile==new File(wxSource.parentFile,'aot').canonicalFile
def cross = harness.runBuild('ProjectB','WeChat','3',true,'TikTok|1.6.0|2|SOURCE_READY')
assert cross.failure
def failed = harness.runBuild('ProjectA','WeChat','4',false,null,'Build')
assert failed.failure
assert !new File(farm,'ProjectA/.jenkins-project.lock').exists()
def collected = harness.runSplit('ProjectA','WeChat','1','SOURCE_READY','启动代码分包采集')
assert !collected.failure : collected.failure
assert ReleaseStorage.read(wxSource).state=='COLLECTING'
harness.failReference = true
def partial = harness.runSplit('ProjectA','WeChat','1','COLLECTING','生成正式分包并上传')
assert partial.failure
assert ReleaseStorage.read(wxSource).state=='COLLECTING'
int uploads = harness.uploads
int finals = harness.finals
harness.failReference = false
def resumed = harness.runSplit('ProjectA','WeChat','1','COLLECTING','生成正式分包并上传')
assert !resumed.failure : resumed.failure
assert harness.uploads==uploads && harness.finals==finals
assert ReleaseStorage.read(wxSource).state=='UPLOADED'
assert partial.env.BUILD_NUMBER != resumed.env.BUILD_NUMBER
assert ReleaseStorage.read(wxSource).uploadedByBuild == partial.env.BUILD_NUMBER.toInteger()
assert ReleaseStorage.read(wxSource).uploadBuildUrl == partial.env.BUILD_URL
assert ReleaseStorage.read(wxSource).lastFinalizedByBuild == partial.env.BUILD_NUMBER.toInteger()

// Recollection can finish remotely and checkpoint successfully, then fail saving Source state.
// Retry must reuse collect-2, preserve null metrics, and not prepare/upload again.
harness.failCollectionState = true
def recollectFailed = harness.runSplit('ProjectA','WeChat','1','UPLOADED','再次启动代码分包采集')
assert recollectFailed.failure?.message?.contains('injected collection state failure')
def checkpoint = ReleaseStorage.read(wxSource).operations['collect-2']
assert checkpoint.status == 'DONE' && ReleaseStorage.read(wxSource).state == 'UPLOADED'
int preparations = harness.preparations
uploads = harness.uploads
harness.failCollectionState = false
def recollectResumed = harness.runSplit('ProjectA','WeChat','1','UPLOADED','再次启动代码分包采集')
assert !recollectResumed.failure : recollectResumed.failure
assert harness.preparations == preparations && harness.uploads == uploads
def cycle2 = ReleaseStorage.read(wxSource)
assert cycle2.state == 'COLLECTING' && cycle2.collectionCycle == 2
assert cycle2.lastUploadedMetrics.collected == null && cycle2.lastUploadedMetrics.isProfile == false
assert cycle2.operations['collect-2'] == checkpoint
assert cycle2.operations['upload-1'].result.build == partial.env.BUILD_NUMBER
assert !harness.runSplit('ProjectA','WeChat','1','COLLECTING','生成正式分包并上传').failure
assert ReleaseStorage.read(wxSource).operations['upload-2'].status == 'DONE'
assert !harness.runSplit('ProjectA','WeChat','1','UPLOADED','再次启动代码分包采集').failure
assert ReleaseStorage.read(wxSource).collectionCycle == 3
assert harness.preparations == preparations + 1
def ttSource = new File(farm,'ProjectA/ReleaseSources/TikTok/1.6.0/2/code-split-state.json')
assert !harness.runSplit('ProjectA','TikTok','2','SOURCE_READY','启动代码分包采集').failure
harness.failUpload = true
assert harness.runSplit('ProjectA','TikTok','2','COLLECTING','生成正式分包并上传').failure
uploads = harness.uploads
harness.failUpload = false
def uncertain = harness.runSplit('ProjectA','TikTok','2','COLLECTING','生成正式分包并上传')
assert uncertain.failure?.message?.contains('未确认')
assert harness.uploads==uploads
assert !new File(farm,'ProjectA/.jenkins-project.lock').exists()
def held = ReleaseStorage.acquire(new File(farm,'ProjectA'),'another-active-job')
def busy = harness.runBuild('ProjectA','WeChat','5')
assert busy.failure && !busy.executed.contains('Clean')
assert ReleaseStorage.read(new File(held.path)).token==held.token
assert !harness.runBuild('ProjectB','TikTok','2').failure
ReleaseStorage.release(held)
// 未填写凭据必须在 Git/Unity 操作和 Source 预留前失败。
['cos','wechat'].eachWithIndex { fault, index ->
    harness.credentialFault = fault
    def number = (90 + index).toString()
    def invalid = harness.runBuild('ProjectB','WeChat',number)
    assert invalid.failure : fault
    assert !invalid.executed.contains('Clean') && !invalid.executed.contains('Build')
    assert !new File(farm,'ProjectB/ReleaseSources/WeChat/1.6.0/'+number).exists()
    assert !new File(farm,'ProjectB/.jenkins-project.lock').exists()
}
harness.credentialFault = null
// 目录存在不等于整次构建成功；缺失、空快照、后续出包失败都不能开放 Source。
['missing','empty','late-failure'].eachWithIndex { fault, index ->
    harness.aotFault=fault
    int uploadsBefore=harness.uploads
    def result=harness.runBuild('ProjectB','WeChat',(100+index).toString())
    assert result.failure : fault
    assert !result.executed.contains('SnapshotReleaseSource') && !result.executed.contains('Upload')
    assert !new File(farm,'ProjectB/ReleaseSources/WeChat/1.6.0/'+(100+index)+'/code-split-state.json').exists()
    assert harness.uploads==uploadsBefore
}
harness.aotFault=null
// 不出小游戏包的全量也显式归档 AOT，但不形成可热更/分包的 Source。
def aotOnly=harness.runBuild('ProjectB','WeChat','103',false,null,null,[params:[BUILD_MINIGAME:false]])
assert !aotOnly.failure : aotOnly.failure
assert new File(aotOnly.outputAot).isDirectory()
assert !new File(new File(aotOnly.outputAot).parentFile,'code-split-state.json').exists()

def savedState=ReleaseStorage.read(wxSource)
def selected='WeChat|1.6.0|1|'+savedState.state
[
    [schemaVersion:5],
    [buildTarget:'StandaloneWindows64'],
    [aotBackupPath:new File(farm,'ProjectB/ReleaseSources/WeChat/1.6.0/1/aot').path]
].eachWithIndex { change,index ->
    ReleaseStorage.writeAtomic(wxSource,savedState+change)
    try {
        def result=harness.runBuild('ProjectA','WeChat',(110+index).toString(),true,selected)
        assert result.failure && !result.executed.contains('Build')
    } finally { ReleaseStorage.writeAtomic(wxSource,savedState) }
}
def fixtureAot=new File(wxSource.parentFile,'aot/framework-owned.fixture')
def savedAot=fixtureAot.text
fixtureAot.text='tampered'
def damaged=harness.runBuild('ProjectA','WeChat','114',true,selected)
assert damaged.failure && !damaged.executed.contains('Build')
fixtureAot.text=savedAot
assert fixtureAot.delete()
def missingInput=harness.runBuild('ProjectA','WeChat','115',true,selected)
assert missingInput.failure && !missingInput.executed.contains('Build')
fixtureAot.text=savedAot
harness.aotFault='mutate-input'
def mutated=harness.runBuild('ProjectA','WeChat','116',true,selected)
assert mutated.failure?.message?.contains('输入 AOT 快照发生变化')
fixtureAot.text=savedAot
harness.aotFault=null
[
    ['-aotBackupInputPath','D:/wrong'],
    ['-aotBackupOutputPath=D:/wrong'],
    ['-aotBackupVersion 9.9.9'],
    ['"-aotBackupInputPath"','D:/wrong'],
    ['-AOTBACKUPVERSION=9.9.9']
].eachWithIndex { extra,index ->
    def result=harness.runBuild('ProjectA','WeChat',(120+index).toString(),true,selected,null,[extraArgs:extra])
    assert result.failure?.message?.contains('extraArgs')
    assert !result.commands.any { it.contains('Example.HotUpdateBuild') }
}
assert !new File(farm,'ProjectA/Workspace/HybridCLRData/AOTBackup').exists()
assert !new File(farm,'ProjectA/AOTBackup').exists()
// Both platforms may fail during generation/download, validation or metrics and retry the same action.
['WeChat','TikTok'].each { platform ->
    ['download','validation','metrics'].eachWithIndex { fault,index ->
        def project='Retry'+index
        assert !harness.runBuild(project,platform,'1').failure
        assert !harness.runSplit(project,platform,'1','SOURCE_READY','启动代码分包采集').failure
        def sourceFile=new File(farm,project+'/ReleaseSources/'+platform+'/1.6.0/1/code-split-state.json')
        def before=ReleaseStorage.read(sourceFile)
        def sub=platform=='WeChat'?'minigame':'tt-minigame'
        def collection=new File(sourceFile.parentFile,'collection/'+sub)
        def aotBefore=harness.fingerprint(new File(sourceFile.parentFile,'aot').path)
        def collectionBefore=harness.fingerprint(collection.path)
        def rawBefore=harness.fingerprint(before.rawPackagePath)
        int preparedBefore=harness.preparations, uploadedBefore=harness.uploads, finalizedBefore=harness.finals
        harness.finalizationFault=fault
        def failedSplit=harness.runSplit(project,platform,'1','COLLECTING','生成正式分包并上传')
        assert failedSplit.failure?.message?.contains('injected '+fault) : failedSplit.failure
        assert !failedSplit.executed.contains('UploadRelease') && harness.uploads==uploadedBefore
        assert ReleaseStorage.read(sourceFile).operations['finalize-1'].status=='STARTED'
        harness.finalizationFault=null
        def retry=harness.runSplit(project,platform,'1','COLLECTING','生成正式分包并上传')
        assert !retry.failure : retry.failure
        def after=ReleaseStorage.read(sourceFile)
        assert after.state=='UPLOADED' && after.collectionCycle==before.collectionCycle
        assert after.operations['collect-1']==before.operations['collect-1']
        assert after.operations['finalize-1'].previousAttempts*.owner==[failedSplit.env.BUILD_URL]
        assert after.operations['finalize-1'].owner==retry.env.BUILD_URL
        assert after.lastFinalizedByBuild==retry.env.BUILD_NUMBER.toInteger()
        assert harness.preparations==preparedBefore && harness.finals==finalizedBefore+2 && harness.uploads==uploadedBefore+1
        assert harness.fingerprint(before.rawPackagePath)==rawBefore
        assert harness.fingerprint(collection.path)==collectionBefore
        assert harness.fingerprint(new File(sourceFile.parentFile,'aot').path)==aotBefore
        assert !new File(sourceFile.parentFile,'release/'+sub+'/incomplete.fixture').exists()
        assert !new File(farm,project+'/.jenkins-project.lock').exists()
    }
    // Completed finalization is reusable when the build stops before upload; changed output is not.
    def project='ReadyToUpload'
    assert !harness.runBuild(project,platform,'1').failure
    assert !harness.runSplit(project,platform,'1','SOURCE_READY','启动代码分包采集').failure
    def stopped=harness.runSplit(project,platform,'1','COLLECTING','生成正式分包并上传','UploadRelease')
    assert stopped.failure
    def sourceFile=new File(farm,project+'/ReleaseSources/'+platform+'/1.6.0/1/code-split-state.json')
    def completed=ReleaseStorage.read(sourceFile).operations['finalize-1']
    assert completed.status=='DONE' && !ReleaseStorage.read(sourceFile).operations['upload-1']
    def sub=platform=='WeChat'?'minigame':'tt-minigame'
    def tamper=new File(sourceFile.parentFile,'release/'+sub+'/tamper.fixture')
    tamper.text='changed'
    int finalCount=harness.finals, uploadCount=harness.uploads
    def damagedSplit=harness.runSplit(project,platform,'1','COLLECTING','生成正式分包并上传')
    assert damagedSplit.failure?.message?.contains('产物被改变')
    assert harness.finals==finalCount && harness.uploads==uploadCount
    assert tamper.delete()
    harness.failUpload=true
    assert harness.runSplit(project,platform,'1','COLLECTING','生成正式分包并上传').failure
    harness.failUpload=false
    assert harness.finals==finalCount && harness.uploads==uploadCount+1
    assert ReleaseStorage.read(sourceFile).operations['finalize-1']==completed
    def uploadUnknown=harness.runSplit(project,platform,'1','COLLECTING','生成正式分包并上传')
    assert uploadUnknown.failure?.message?.contains('未确认')
    assert harness.finals==finalCount && harness.uploads==uploadCount+1
}
println "PASS pipeline flow: $harness.runs runs; projects/platforms; full/hot/split; explicit AOT paths; no AOT copy; failed publication; old/tampered/foreign Source; argument guards; JSONNull; recollection and finalization retries; upload uncertainty blocked"
println "Fixtures: $farm"

class FlowHarness {
    File repo, farm
    int uploads=0, finals=0, preparations=0, sequence=100, runs=0
    boolean failReference=false, failUpload=false, failCollectionState=false
    String aotFault=null
    String credentialFault=null
    String finalizationFault=null
    String commit='a'*40
    String md5='0123456789abcdef'

    void setup(String project) {
        def secrets=new File(farm,project+'/Secrets');secrets.mkdirs()
        ['upload.key','split.key'].each { new File(secrets,it).text='fixture' }
        new File(secrets,'cos.yaml').text='''cos:
  base: {secretid: fixture-id, secretkey: fixture-key, protocol: https}
  buckets:
    - {name: fixture-123, endpoint: cos.fixture.invalid}
'''
        if (credentialFault == 'cos') new File(secrets,'cos.yaml').text='cos: {}'
        if (credentialFault == 'wechat') new File(secrets,'upload.key').text=''
        new File(secrets,'credentials.local.json').text=JsonOutput.toJson([
            cos:[configPath:'cos.yaml'],
            wechat:[appId:'fake',uploadKeyPath:'upload.key',wasmSplitKeyPath:'split.key',uploadRobot:1,previewRobot:2],
            douyin:[appId:'fake'],feishu:[enabled:false]])
        new File(farm,project+'/Workspace').mkdirs()
    }
    Map runBuild(String project,String platform,String number,boolean hot=false,String source=null,String failStage=null,Map options=[:]) {
        setup(project)
        def params=[PLATFORM:platform,ENV:'Dev',DEBUG:false,CLEAN_LIBRARY:false,
            FORCE_REBUILD:false,BUILD_MINIGAME:true,PLAN_VERSION:'1.6.1',SOURCE:source] + (options.params ?: [:])
        execute('unityMiniGamePipeline',project,number,params,[
            projectName:project,displayName:project,platforms:['WeChat','TikTok'],coreVersion:options.coreVersion ?: '1.6.0',
            buildType:hot?'HotUpdateBuild':'FullBuild',buildClass:'Example',repoUrl:'fake',
            unityPath:'fixture-unity',buildFarmRoot:farm.path,extraArgs:{options.extraArgs ?: []}],failStage)
    }
    Map runSplit(String project,String platform,String sourceNumber,String state,String action,String failStage=null) {
        execute('codeSplitPipeline',project,(++sequence).toString(),
            [PLATFORM:platform,SOURCE:[platform,'1.6.0',sourceNumber,state].join('|'),ACTION:action],
            [projectName:project,displayName:project,platforms:['WeChat','TikTok'],buildFarmRoot:farm.path],failStage)
    }
    void copyTree(String from,String to) {
        def src=new File(from);def dst=new File(to)
        assert src.isDirectory()
        assert dst.canonicalPath.startsWith(farm.canonicalPath+File.separator)
        dst.mkdirs()
        src.eachFileRecurse(groovy.io.FileType.FILES) { f ->
            def target=new File(dst,src.toPath().relativize(f.toPath()).toString())
            target.parentFile.mkdirs();target.bytes=f.bytes
        }
    }
    String fingerprint(String path) {
        def root=new File(path);assert root.isDirectory()
        def entries=[]
        root.eachFileRecurse(groovy.io.FileType.FILES) { f -> entries << root.toPath().relativize(f.toPath()).toString()+':'+f.text }
        MessageDigest.getInstance('SHA-256').digest(entries.sort().join('\n').getBytes('UTF-8')).encodeHex().toString()
    }
    Map execute(String name,String project,String number,Map params,Map cfg,String failStage) {
        def workspace=new File(farm,project+'/Workspace')
        runs++
        def result=[failure:null,executed:[],commands:[],env:[BUILD_NUMBER:number,BUILD_URL:'test/'+project+'/'+number,
            BUILD_TAG:'test-'+number,WORKSPACE:workspace.path],posts:[:]]
        String current=''
        boolean allow=true
        def localFile={path -> def f=new File(path.toString());f.isAbsolute()?f:new File(workspace,path.toString())}
        def binding=new Binding([params:params,env:result.env,WORKSPACE:workspace.path,
            currentBuild:[durationString:'1 sec',description:'']])
        def shell=new GroovyShell(this.class.classLoader,binding)
        binding.setVariable('libraryResource',{path->new File(repo,'resources/'+path).getText('UTF-8')})
        binding.setVariable('error',{message->throw new IllegalStateException(message.toString())})
        binding.setVariable('echo',{message->})
        binding.setVariable('pipeline',{body->body()})
        ['agent','environment','options'].each { key->binding.setVariable(key,{body->}) }
        binding.setVariable('stages',{body->body()})
        binding.setVariable('stage',{String stage,Closure body ->
            current=stage;allow=!result.failure
            if(allow) { try { body() } catch(Throwable failure) {result.failure=failure} }
        })
        binding.setVariable('when',{body->body()})
        binding.setVariable('expression',{body->allow=allow && body()})
        binding.setVariable('steps',{body->
            if(allow) {
                result.executed<<current
                if(current==failStage) throw new IOException('injected stage failure')
                body()
            }
        })
        binding.setVariable('script',{body->body()})
        ['always','success','failure','cleanup'].each { key->binding.setVariable(key,{body->result.posts[key]=body}) }
        binding.setVariable('post',{body->
            body()
            try {
                result.posts.always?.call()
                result.posts[result.failure?'failure':'success']?.call()
            } finally { result.posts.cleanup?.call() }
        })
        binding.setVariable('disableConcurrentBuilds',{->[:]})
        binding.setVariable('properties',{value->})
        binding.setVariable('parameters',{value->value})
        binding.setVariable('archiveArtifacts',{value->})
        binding.setVariable('withEnv',{settings,body->body()})
        binding.setVariable('pwd',{->workspace.path})
        binding.setVariable('fileExists',{path->localFile(path).exists()})
        binding.setVariable('readFile',{path->
            def p=path instanceof Map?path.file:path
            p.toString().contains('ProjectSettings.asset')?
                'WeixinMiniGame: JULYGF_WX_MINIGAME;JULYGF_DY_MINIGAME':localFile(p).getText('UTF-8')
        })
        binding.setVariable('readProperties',{settings->
            def map=[:];localFile(settings.file).eachLine {line->def p=line.split('=',2);if(p.size()==2)map[p[0]]=p[1]};map
        })
        binding.setVariable('writeFile',{settings->
            def f=localFile(settings.file);f.parentFile.mkdirs();f.setText(settings.text.toString(),'UTF-8')
        })
        binding.setVariable('readJSON',{settings->
            def text=localFile(settings.file).getText('UTF-8')
            settings.returnPojo == true ? new JsonSlurperClassic().parseText(text) : JSONSerializer.toJSON(text)
        })
        binding.setVariable('bat',{arg->
            def map=arg instanceof Map?arg:[:];def cmd=arg instanceof Map?arg.script:arg.toString()
            result.commands << cmd
            if(cmd.contains('fixture-unity') && cmd.contains('Example.FullBuild')) {
                assert !cmd.contains('-aotBackupInputPath') && !cmd.contains('-aotBackupVersion')
                def match=cmd =~ /-aotBackupOutputPath\s+"([^"]+)"/
                assert match.find() : 'Missing quoted output argument'
                def aot=new File(match.group(1))
                assert aot.canonicalFile==new File(farm,project+'/ReleaseSources/'+params.PLATFORM+'/'+cfg.coreVersion+'/'+number+'/aot').canonicalFile
                assert !aot.exists() : 'Jenkins must not create the framework output directory'
                result.outputAot=aot.path
                if(aotFault != 'missing') {
                    aot.mkdirs()
                    if(aotFault != 'empty') {
                        // 故意使用不透明格式；真实清单由 com.july.release 自己定义和校验。
                        new File(aot,'framework-owned.fixture').text=project+':'+params.PLATFORM+':'+number
                    }
                }
                if(aotFault=='late-failure') throw new IOException('framework failed after publishing AOT')
                if(params.BUILD_MINIGAME) {
                    def sub=params.PLATFORM=='TikTok'?'tt-minigame':'minigame'
                    def output=new File(farm,project+'/Framework Exports/'+params.PLATFORM+'/'+cfg.coreVersion+'/'+sub+'/wasmcode')
                    output.mkdirs();new File(output,md5+'.wasm').text='wasm'
                }
            }
            if(cmd.contains('fixture-unity') && cmd.contains('Example.HotUpdateBuild')) {
                assert !cmd.contains('-aotBackupOutputPath')
                assert cmd.contains('-aotBackupVersion 1.6.0') && cmd.contains('-buildTarget MiniGame')
                def match=cmd =~ /-aotBackupInputPath\s+"([^"]+)"/
                assert match.find() : 'Missing quoted input argument'
                def aot=new File(match.group(1))
                assert aot.isDirectory()
                result.inputAot=aot.path
                // Jenkins 只传递基线；此模拟器不实现框架自己的 AOT 恢复。
                if(aotFault=='mutate-input') new File(aot,'framework-owned.fixture').text='changed'
            }
            if(cmd.contains('fixture-unity') && (cmd.contains('Example.FullBuild') || cmd.contains('Example.HotUpdateBuild'))) {
                assert cmd.contains('-uploadCdn')
                def argument = { flag -> def match=cmd =~ /${flag}\s+([^\s]+)/; assert match.find(); match.group(1) }
                boolean full=cmd.contains('Example.FullBuild')
                String platform=argument('-platform')
                def packageDir=full && params.BUILD_MINIGAME ? new File(farm,project+'/Framework Exports/'+platform+'/'+cfg.coreVersion+'/'+(platform=='TikTok'?'tt-minigame':'minigame')).path : ''
                if(packageDir) new File(packageDir,'game.js').text='fixture'
                def report=[schemaVersion:1,succeeded:true,cdnUploaded:true,
                    buildType:full?'FullBuild':'HotUpdateBuild',platform:platform,buildTarget:'MiniGame',
                    environment:argument('-env'),coreVersion:cfg.coreVersion,planVersion:argument('-planVersion'),
                    debug:cmd.contains('-debug'),packageDirectory:packageDir,
                    aotBackupPath:full?result.outputAot:result.inputAot,cdnUrl:'https://cdn.example/'+project]
                localFile('release-build-result.json').text=JsonOutput.toJson(report)
            }
            if(map.returnStatus) return 0
            if(cmd.contains('git rev-parse HEAD')) return commit
            ''
        })
        ['releaseProject','releaseCredentials','releaseParameters','releaseState','unityMiniGameSourceScript','releaseUnityResult','releaseGitTag'].each { module ->
            binding.setVariable(module,shell.parse(new File(repo,'vars/'+module+'.groovy')))
        }
        result.completeFull = { String path -> binding.getVariable('releaseGitTag').completeFull(path) }
        result.completeResources = { String path -> binding.getVariable('releaseGitTag').completeResources(path) }
        def stateModule = binding.getVariable('releaseState')
        binding.setVariable('releaseState',[
            once:{path,operation,body->stateModule.once(path,operation,body)},
            finalizePackage:{path,operation,body->stateModule.finalizePackage(path,operation,body)},
            update:{path,changes->
                if(failCollectionState && changes.state=='COLLECTING')
                    throw new IOException('injected collection state failure')
                stateModule.update(path,changes)
            }
        ])
        binding.setVariable('releaseJenkinsSecrets',[
            check:{kind->assert kind in ['git','douyin']},
            withPair:{kind,first,second,body->assert kind in ['git','douyin'];body()}
        ])
        binding.setVariable('releaseFiles',[
            directoryFingerprint:{path->fingerprint(path)},extractWasmMd5:{path->md5},
            mirrorDirectory:{from,to,root->
                assert !from.replace('\\\\','/').endsWith('/aot') : 'Jenkins must not copy AOT'
                copyTree(from,to)
            }
        ])
        def platformAdapter=[
            prepare:{c->preparations++;copyTree(c.raw,c.collection)},
            finalizePackage:{c->
                finals++
                def release=new File(c.release)
                assert release.canonicalPath.startsWith(farm.canonicalPath+File.separator)
                assert release.parentFile.name=='release' && release.name in ['minigame','tt-minigame']
                if(release.exists()) assert release.deleteDir()
                copyTree(params.PLATFORM=='WeChat'?c.collection:c.raw,c.release)
                if(finalizationFault=='download') {
                    new File(release,'incomplete.fixture').text='partial download'
                    throw new IOException('injected download failure')
                }
                '7'
            },
            upload:{c->uploads++;if(failUpload)throw new IOException('remote result unknown')},
            preview:{c->localFile('preview_qr.jpg').text='fake'},
            withSession:{c,body->body()}
        ]
        binding.setVariable('releaseWeChat',platformAdapter)
        binding.setVariable('releaseTikTok',platformAdapter)
        binding.setVariable('releaseSplitMetrics',[
            runWasmGetinfo:{a,b,c,d,e->
                if(current=='FinalSplit' && finalizationFault=='metrics') throw new IOException('injected metrics failure')
            },readWasmGameInfo:{a,b->[total:100,current:40,increase:2,isProfile:false,collected:null]},
            validateReleasePackage:{a,b->
                if(finalizationFault=='validation') throw new IOException('injected validation failure')
            },formatWasmSummary:{a,b->'summary'},formatWasmMetrics:{a->'summary'},
            formatWasmInfoBlock:{a,b,c->'summary'},
            saveWeChatRefMd5:{a,b->if(failReference)throw new IOException('reference write failed')},
            saveTikTokRefVersion:{a,b->if(failReference)throw new IOException('reference write failed')}
        ])
        try { shell.parse(new File(repo,'vars/'+name+'.groovy')).call(cfg) }
        catch(Throwable failure) {result.failure=failure}
        result
    }
}
