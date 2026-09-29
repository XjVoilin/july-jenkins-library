def repo = new File(args[0])
def temp = File.createTempDir('release-adapters-', '')
def commands=[]
def checkpointFile = new File(temp,'checkpoint.json')
boolean checkOrder=false, failLocal=false, failInit=false
def store=org.july.release.ReleaseStorage
boolean failLogin=false
boolean failUpload=false
boolean failFinalize=false
def binding=new Binding([env:[BUILD_URL:'test/adapter'],echo:{m->},
    pwd:{arg->temp.path},writeFile:{arg->},readFile:{arg->'OK'},
    dir:{path,body->body()},deleteDir:{->},
    error:{m->throw new IllegalStateException(m.toString())},
    withEnv:{values,body->body()},
    fileExists:{path->false},
    readJSON:{settings->[isProfile:true,hasUsedWasmCodeSplit:true]},
    bat:{arg->
        def text=arg instanceof Map?arg.script.toString():arg.toString()
        commands<<text
        if(checkOrder) {
            def checkpoint=store.read(checkpointFile).operations?.'collect-2'
            if(text.contains('tmg login-e') || text.contains('robocopy ')) assert !checkpoint
            if(text.contains('tt-wasmsplit-ci init')) {
                assert checkpoint.status=='STARTED'
                assert new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
            }
            if(failLocal && text.contains('robocopy ')) throw new IOException('local preparation failed')
            if(failInit && text.contains('tt-wasmsplit-ci init')) throw new IOException('remote outcome unknown')
        }
        if(failLogin && text.contains('tmg login-e')) return -1073740791
        if(failUpload && text.contains('tmg upload')) throw new IOException('mock CLI failure')
        if(failFinalize && text.contains(' dosplit ')) throw new IOException('mock download failure')
        arg instanceof Map && arg.returnStatus ? 0 : 'Upload success\ncurrent split version: 7'
    }])
def shell=new GroovyShell(this.class.classLoader,binding)
['releaseState','releaseCredentials','releaseFiles','releaseSplitMetrics','releaseWeChat','releaseTikTok'].each {name->
    binding.setVariable(name,shell.parse(new File(repo,'vars/'+name+'.groovy')))
}
binding.setVariable('releaseJenkinsSecrets',[withPair:{kind,first,second,body->
    assert kind=='douyin' && first=='DY_EMAIL' && second=='DY_PASSWORD';body()
}])
def context={platform->
    def sources=new File(temp,'P/ReleaseSources')
    def root=new File(sources,platform+'/1.6.0/1')
    def sub=platform=='TikTok'?'tt-minigame':'minigame'
    def secrets=new File(temp,'P/Secrets');secrets.mkdirs()
    ['upload.key','split.key'].each {new File(secrets,it).text='fixture'}
    ['raw','collection','release'].each {new File(root,it+'/'+sub).mkdirs()}
    [farmRoot:temp.path,sources:sources.path,secretsRoot:secrets.path,
        raw:new File(root,'raw/'+sub).path,collection:new File(root,'collection/'+sub).path,
        release:new File(root,'release/'+sub).path,packageDir:new File(root,'release/'+sub).path,
        appId:'wx-fixture',uploadKey:'upload.key',splitKey:'split.key',ttAppId:'tt-fixture',
        version:'1.6.0',sourceBuild:'1',
        robot:1,previewRobot:2,qrcode:new File(temp,'preview.jpg').path,
        description:'fixture',refRoot:new File(temp,'P/Cache/'+platform).path,restart:false]
}
def wx=context('WeChat')
binding.releaseWeChat.upload(wx)
assert commands.last().contains('--appid "%WX_APPID%"') && commands.last().contains('-r 1')
binding.releaseWeChat.preview(wx)
assert commands.last().contains('miniprogram-ci preview') && commands.last().contains('-r 2')
binding.releaseWeChat.finalizePackage(wx)
assert commands.last().contains('--release')
def tt=context('TikTok')
assert binding.releaseTikTok.finalizePackage(tt)=='7'
assert commands.last().contains('-i tt-fixture')
binding.releaseTikTok.upload(tt)
assert commands.last().contains('tmg upload')
binding.releaseTikTok.preview(tt)
assert commands.last().contains('--disable-cache')
binding.releaseTikTok.prepare(tt)
assert commands.last().contains('tt-wasmsplit-ci init')
assert !commands.last().contains('wx-fixture')
assert !new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
// Retry uses the unchanged official command and mirrors a clean baseline every time.
[[adapter:binding.releaseWeChat,c:wx,baseline:wx.collection],
 [adapter:binding.releaseTikTok,c:tt,baseline:tt.raw]].each { test ->
    commands.clear()
    failFinalize=true
    try { test.adapter.finalizePackage(test.c);assert false } catch(IOException expected) {}
    failFinalize=false
    test.adapter.finalizePackage(test.c)
    def mirrors=commands.findAll { it.contains('robocopy ') }
    assert mirrors.size()==2 && mirrors.every {
        it.contains('robocopy "'+test.baseline+'" "'+test.c.release+'" /MIR') &&
        it.contains('rmdir /s /q "'+test.c.release+'"')
    }
    assert commands.count { it.contains(' dosplit ') }==2
    assert !commands.any { it.contains(' upload ') || it.contains(' init ') }
    assert !new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
}
failUpload=true
try {binding.releaseTikTok.upload(tt);assert false}catch(IOException expected){}
assert !new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
failUpload=false
failLogin=true
commands.clear()
try { binding.releaseTikTok.prepare(tt); assert false }
catch(IllegalStateException expected) { assert expected.message.contains('0xC0000409') }
assert !commands.any { it.contains('tt-wasmsplit-ci init') }
assert !new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
// Real adapter and checkpoint wrapper: login/local errors write nothing; remote errors retain STARTED.
checkOrder=true
failLogin=false
failUpload=false
def baseline=[state:'UPLOADED',platform:'TikTok',collectionCycle:1]
store.writeAtomic(checkpointFile,baseline)
def prepareOnce = {
    binding.releaseState.oncePrepared(checkpointFile.path,'collect-2') { start ->
        binding.releaseTikTok.prepare(tt,start)
        [prepared:true]
    }
}
['login','local'].each { failure ->
    def before=checkpointFile.text
    failLogin=failure=='login';failLocal=failure=='local'
    try { prepareOnce();assert false } catch(IllegalStateException | IOException expected) {}
    assert checkpointFile.text==before
    assert !new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
}
failLogin=false;failLocal=false
assert prepareOnce().prepared
assert store.read(checkpointFile).operations.'collect-2'.status=='DONE'
commands.clear();failLogin=true
assert prepareOnce().prepared && commands.empty // DONE bypasses login and mirroring entirely.
failLogin=false
store.writeAtomic(checkpointFile,baseline)
failInit=true
try { prepareOnce();assert false } catch(IOException expected) {}
assert store.read(checkpointFile).operations.'collect-2'.status=='STARTED'
commands.clear();failInit=false
try { prepareOnce();assert false } catch(IllegalStateException expected) {}
assert commands.empty // Unknown outcome is rejected before login or local mutation.
assert !new File(temp,'ToolSessions/TikTok/.jenkins-project.lock').exists()
checkOrder=false
// Exit-code-zero failures and missing success markers must still fail closed.
binding.setVariable('bat',{args->'Error: mock failure'})
try {binding.releaseFiles.runCheckedBat('fixture','unused');assert false}catch(IllegalStateException expected){}
binding.setVariable('bat',{args->'unexpected output'})
try {binding.releaseFiles.runCheckedBat('fixture','unused',['Upload success']);assert false}catch(IllegalStateException expected){}
println 'PASS adapters: actual platform command generation, app separation, robots, CLI markers, login-lock cleanup'
