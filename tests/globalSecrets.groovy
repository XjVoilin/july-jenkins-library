import org.july.release.JenkinsSecretPolicy
import groovy.json.JsonOutput

def repo=new File(args[0])
def ids=JenkinsSecretPolicy.ids('git')+JenkinsSecretPolicy.ids('douyin')
assert ids==['build-git-username','build-git-password','build-douyin-email','build-douyin-password']
def meta=ids.collect { [id:it,secretText:true,globalScope:true] }
JenkinsSecretPolicy.validate(ids,meta,[])
def reject={body->try{body();assert false}catch(IllegalStateException expected){}}
reject { JenkinsSecretPolicy.validate(ids,meta.tail(),[]) }
reject { JenkinsSecretPolicy.validate(ids,meta+meta[0],[]) }
reject { JenkinsSecretPolicy.validate(ids,meta.collect { it.id==ids[0]?it+[secretText:false]:it },[]) }
reject { JenkinsSecretPolicy.validate(ids,meta.collect { it.id==ids[0]?it+[globalScope:false]:it },[]) }
reject { JenkinsSecretPolicy.validate(ids,meta,[ids[0]]) }

def descriptors=[],checked=[],commands=[],files=[:],scopes=[]
def env=[JOB_NAME:'ProjectA/1.6.0_FullBuild']
def values=[(ids[0]):'private-user-fixture',(ids[1]):'private-password-fixture',
            (ids[2]):'private-email-fixture',(ids[3]):'private-douyin-password-fixture']
def binding=new Binding([env:env,
    error:{m->throw new IllegalStateException(m.toString())},
    string:{m->m},
    withCredentials:{bindings,body->
        descriptors<<bindings
        bindings.each { env[it.variable]=values[it.credentialsId] }
        try {body()} finally {bindings.each{env.remove(it.variable)}}
    },
    withEnv:{settings,body->scopes<<settings;body()},
    writeFile:{m->files[m.file]=m.text},
    pwd:{->'D:\\fixture-workspace'},
    bat:{m->commands<<m}
])
def shell=new GroovyShell(this.class.classLoader,binding)
def secrets=shell.parse(new File(repo,'vars/releaseJenkinsSecrets.groovy'))
secrets.metaClass.check={String kind->checked<<kind}
binding.setVariable('releaseJenkinsSecrets',secrets)
def credentials=shell.parse(new File(repo,'vars/releaseCredentials.groovy'))
credentials.withGitAuthentication {
    assert env.GIT_USER==values[ids[0]] && env.GIT_PASS==values[ids[1]]
}
assert checked==['git']
assert descriptors[0]*.credentialsId==ids.take(2)
assert !env.containsKey('GIT_PASS')
assert scopes[0].containsAll(['GIT_TERMINAL_PROMPT=0','GIT_CONFIG_COUNT=0',
                            "GIT_CONFIG_PARAMETERS='credential.helper='"])
assert files.size()==2 && commands.last().contains('del /q')
assert values.values().every { v->!files.values().any { it.contains(v) } }
try {credentials.withGitAuthentication {throw new IOException('fixture failure')};assert false}
catch(IOException expected){}
assert !env.containsKey('GIT_USER') && commands.size()==2
secrets.withPair('douyin','DY_EMAIL','DY_PASSWORD') {assert env.DY_EMAIL==values[ids[2]]}
assert descriptors.last()*.credentialsId==ids.drop(2)
assert !env.containsKey('DY_EMAIL')

// 明文/项目凭证 ID 均拒绝；错误消息不得包含私人值。
def root=File.createTempDir('release-secrets-', '')
def dir=new File(root,'ProjectA/Secrets');dir.mkdirs()
def projectBinding=new Binding([error:{m->throw new IllegalStateException(m.toString())}])
def project=new GroovyShell(this.class.classLoader,projectBinding).parse(new File(repo,'vars/releaseProject.groovy'))
['username','password','usernameCredentialsId','passwordCredentialsId'].each {key->
    new File(dir,'credentials.local.json').text=JsonOutput.toJson([feishu:[enabled:false],git:[(key):'private-fixture']])
    try {project.load([projectName:'ProjectA',buildFarmRoot:root.path]);assert false}
    catch(IllegalStateException expected){assert !expected.message.contains('private-fixture')}
}
['email','password','emailCredentialsId','passwordCredentialsId'].each {key->
    new File(dir,'credentials.local.json').text=JsonOutput.toJson([feishu:[enabled:false],douyin:[(key):'private-fixture']])
    try {project.load([projectName:'ProjectA',buildFarmRoot:root.path]);assert false}
    catch(IllegalStateException expected){assert !expected.message.contains('private-fixture')}
}
// 项目入口统一校验：缺失/非布尔通知开关和非法项目名仍必须失败。
def configFile=new File(dir,'credentials.local.json')
[[:], [feishu:[:]], [feishu:[enabled:null]], [feishu:[enabled:'false']],
 [feishu:[enabled:0]], [feishu:'false']].each { data ->
    configFile.text=JsonOutput.toJson(data)
    reject { project.load([projectName:'ProjectA',buildFarmRoot:root.path]) }
}
[true,false].each { enabled ->
    configFile.text=JsonOutput.toJson([feishu:[enabled:enabled],douyin:[appId:'fixture']])
    assert project.load([projectName:'ProjectA',buildFarmRoot:root.path]).credentials.feishu.enabled==enabled
}
['../ProjectB','',null,'Project/A'].each { name ->
    reject { project.load([projectName:name,buildFarmRoot:root.path]) }
}
println 'PASS global secrets: global-only metadata, collisions/types/scopes, fixed IDs, binding cleanup, no plaintext scripts, rejected legacy JSON, centralized project validation'
