// 默认只检查。修改操作必须显式填写 action、reason、预期 owner/token，并确认远端无副作用。
import jenkins.model.Jenkins
import hudson.model.Job
import groovy.json.JsonOutput
def cfg=binding.hasVariable('recovery') ? recovery : [:]
def j=Jenkins.get()
j.checkPermission(Jenkins.ADMINISTER)
def project=cfg.projectName?.toString()
assert project ==~ /[A-Za-z0-9][A-Za-z0-9_.-]*/ : 'projectName required'
def loader=new GroovyClassLoader(this.class.classLoader)
loader.addClasspath(new File((cfg.libraryRoot ?: 'D:/Jenkins/shared-library').toString(),'src').path)
def store=loader.loadClass('org.july.release.ReleaseStorage')
def farm=new File((cfg.buildFarmRoot ?: 'D:/BuildFarm').toString())
def root=store.inside(farm,new File(farm,project))
def scope=(cfg.scope ?: 'project').toString()
assert scope in ['project','tiktok-session'] : 'scope must be project or tiktok-session'
def lockRoot=scope=='project' ? root : store.inside(farm,new File(farm,'ToolSessions/TikTok'))
def lockFile=new File(lockRoot,'.jenkins-project.lock')
def action=(cfg.action ?: 'inspect').toString()
def stateFile=null
if(cfg.platform || cfg.coreVersion || cfg.sourceBuild) {
    assert cfg.platform in ['WeChat','TikTok']
    assert cfg.coreVersion?.toString() ==~ /\d+\.\d+\.\d+/
    assert cfg.sourceBuild?.toString() ==~ /[1-9]\d*/
    def sources=new File(root,'ReleaseSources')
    stateFile=store.inside(sources,new File(sources,[cfg.platform,cfg.coreVersion,cfg.sourceBuild,'code-split-state.json'].join('/')))
}
if(action=='inspect') {
    println JsonOutput.prettyPrint(JsonOutput.toJson([
        project:project,scope:scope,lock:lockFile.isFile()?store.read(lockFile):null,
        source:stateFile?.path,operations:stateFile?.isFile()?store.read(stateFile).operations:null]))
    return
}
assert cfg.reason?.toString()?.trim() : 'reason required'
assert !j.getAllItems(Job).any { it.isBuilding() } : 'Recovery requires all builds to be idle'
if(action=='release-stale-lock') {
    assert lockFile.isFile()
    def lease=store.read(lockFile)
    assert new File(lease.path.toString()).canonicalFile==lockFile.canonicalFile : 'lock path mismatch'
    assert cfg.expectedToken && cfg.expectedToken==lease.token : 'token mismatch'
    assert cfg.expectedOwner && cfg.expectedOwner==lease.owner : 'owner mismatch'
    def backup=new File(lockRoot,'lock-recovery-'+System.currentTimeMillis()+'.json')
    store.writeAtomic(backup,[lease:lease,reason:cfg.reason,admin:Jenkins.getAuthentication2().name])
    store.release(lease)
    println 'Released stale '+scope+' lock; backup: '+backup
    return
}
assert action=='retry-unapplied-operation' : 'Unknown action'
assert scope=='project' : 'Source recovery requires project scope'
assert cfg.confirmNoRemoteEffect==true : 'Confirm the remote operation did NOT take effect before retry'
assert stateFile?.isFile() && cfg.operation && cfg.expectedOwner
def lease=store.acquire(root,'admin-recovery')
try {
    def state=store.read(stateFile)
    def operation=state.operations?.get(cfg.operation)
    assert operation?.status=='STARTED' && operation.owner==cfg.expectedOwner : 'operation mismatch'
    store.writeAtomic(new File(stateFile.parentFile,'before-recovery-'+System.currentTimeMillis()+'.json'),state)
    state.operations.remove(cfg.operation)
    def history=(state.recoveries ?: [])+[[operation:cfg.operation,owner:cfg.expectedOwner,
        reason:cfg.reason,admin:Jenkins.getAuthentication2().name,time:System.currentTimeMillis()]]
    store.update(stateFile,[operations:state.operations,recoveries:history])
    println 'Operation cleared for explicit retry. No build was triggered.'
} finally {store.release(lease)}
