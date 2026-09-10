import groovy.json.JsonSlurperClassic
import org.july.release.SecretFiles

def repo = new File(args[0])
def root = File.createTempDir('release-secret-skeleton-', '')
int checks = 0
def check = { boolean condition -> assert condition; checks++ }
def reject = { String fragment, Closure action ->
    try { action(); assert false : 'Expected rejection' }
    catch (IllegalStateException expected) {
        assert expected.message.contains(fragment)
        assert !expected.message.contains('private-fixture')
        checks++
    }
}
def runInit = { String projectName, boolean failWrite ->
    def project = new File(root, projectName)
    def marker = new File(project, '.jenkins-project-initializing')
    def binding = new Binding([
        libraryResource: { path -> new File(repo, 'resources/' + path).getText('UTF-8') },
        writeFile: { args ->
            if (failWrite && args.file.toString().endsWith('cos.yaml'))
                throw new IOException('injected write failure')
            new File(args.file.toString()).setText(args.text.toString(), 'UTF-8')
        },
        bat: { args ->
            if (args.label == '初始化 BuildFarm 项目目录') {
                if (project.exists()) throw new IllegalStateException('already exists')
                project.mkdirs(); marker.text = ''
                ['Workspace', 'Build', 'ReleaseSources', 'Cache', 'Secrets'].each {
                    new File(project, it).mkdirs()
                }
            } else if (args.label == '完成项目初始化') {
                assert marker.delete()
            } else if (args.label == '回滚未完成的项目目录') {
                if (marker.exists()) {
                    assert project.canonicalPath.startsWith(root.canonicalPath + File.separator)
                    assert project.deleteDir()
                }
            } else { assert false : args.label }
        }
    ])
    def initializer = new GroovyShell(this.class.classLoader, binding).parse(
        new File(repo, 'vars/unityMiniGameProjectJobs.groovy'))
    initializer.invokeMethod('createProjectDirectory', [project.path,
        new File(project, 'Secrets/credentials.local.json').path, root.path] as Object[])
    return project
}
def project = runInit('ProjectA', false)
def secrets = new File(project, 'Secrets')
check(secrets.list().toList().toSet() == ['credentials.local.json', 'wechat-upload.key', 'wechat-wasm-split.key', 'cos.yaml'].toSet())
def data = new JsonSlurperClassic().parse(new File(secrets, 'credentials.local.json'), 'UTF-8')
check(!data.containsKey('_comment') && !data.containsKey('git'))
check(data.douyin.keySet() == ['appId'].toSet())
check(data.wechat.uploadKeyPath == 'wechat-upload.key' && data.wechat.wasmSplitKeyPath == 'wechat-wasm-split.key')
check(data.cos.configPath == 'cos.yaml' && data.feishu.enabled == false)
check(!new File(project, '.jenkins-project-initializing').exists())
['wechat-upload.key', 'wechat-wasm-split.key'].each { name ->
    def file = new File(secrets, name)
    check(file.length() == 0)
    reject('未填写') { SecretFiles.validate(file, 'wechat.uploadKeyPath') }
    file.setText('\uFEFF \r\n\t', 'UTF-8')
    reject('未填写') { SecretFiles.validate(file, 'wechat.uploadKeyPath') }
    file.setText('private-fixture', 'UTF-8')
    SecretFiles.validate(file, 'wechat.uploadKeyPath'); checks++
}
def cos = new File(secrets, 'cos.yaml')
reject('占位符') { SecretFiles.validate(cos, 'cos.configPath') }
def valid = '''cos:
  base:
    secretid: private-fixture-id
    secretkey: private-fixture-key
    sessiontoken: ""
    protocol: https
  buckets:
    - name: fixture-123
      alias: ""
      region: ""
      endpoint: cos.fixture.invalid
'''
cos.setText(valid, 'UTF-8')
SecretFiles.validate(cos, 'cos.configPath'); checks++
cos.setText(valid.replace('region: ""', 'region: ap-fixture').replace('endpoint: cos.fixture.invalid', 'endpoint: ""'), 'UTF-8')
SecretFiles.validate(cos, 'cos.configPath'); checks++
[
    [text: valid.replace('secretkey: private-fixture-key', 'secretkey: ""'), message: 'secretkey'],
    [text: valid.replace('endpoint: cos.fixture.invalid', 'endpoint: ""'), message: 'endpoint 或 region'],
    [text: valid.replace('name: fixture-123', 'name: ""'), message: '.name'],
    [text: valid.replace('protocol: https', 'protocol: ftp'), message: 'protocol'],
    [text: '# private-fixture comment only', message: 'cos.base'],
    [text: 'cos: [private-fixture', message: 'YAML 格式错误'],
    [text: 'cos: private-fixture\ncos: other', message: 'YAML 格式错误'],
    [text: '!!java.net.URL [private-fixture]', message: 'YAML 格式错误']
].each { scenario ->
    cos.setText(scenario.text, 'UTF-8')
    reject(scenario.message) { SecretFiles.validate(cos, 'cos.configPath') }
}
cos.setText(valid, 'UTF-8')
def original = new File(secrets, 'wechat-upload.key').text
reject('already exists') { runInit('ProjectA', false) }
check(new File(secrets, 'wechat-upload.key').text == original)
try { runInit('ProjectB', true); assert false }
catch (IOException expected) { check(!new File(root, 'ProjectB').exists()) }
println "PASS project secrets: $checks checks; four-file skeleton, no comment, relative paths, empty keys, COS validation, redacted errors, collision and failure cleanup"
println "Fixtures: $root"
