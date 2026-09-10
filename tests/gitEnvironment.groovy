import hudson.EnvVars
import java.util.concurrent.TimeUnit

// 真实 Jenkins 空值处理 + 本机 Git/ASKPASS；隔离系统/global 配置，不访问远端。
def repo = new File(args[0])
def root = File.createTempDir('release-git-env-', '')
def workspace = new File(root, 'workspace with spaces'); workspace.mkdirs()
def global = new File(root, 'fixture.gitconfig')
global.setText('[credential]\n\thelper = "!echo CACHE_HELPER_MUST_NOT_RUN >&2; exit 97"\n', 'UTF-8')
def base = new EnvVars(System.getenv())
base.keySet().findAll { it.toUpperCase().startsWith('GIT_') }.toList().each { base.remove(it) }
base.putAll(['GIT_CONFIG_NOSYSTEM':'1', 'GIT_CONFIG_GLOBAL':global.path, 'GIT_TERMINAL_PROMPT':'0'])
def environment = new EnvVars(base)
def run = { List command, EnvVars vars, String input = '' ->
    def builder = new ProcessBuilder(command.collect { it.toString() }).directory(workspace)
    builder.environment().clear(); builder.environment().putAll(vars)
    def process = builder.start()
    def stdout = new StringBuffer(), stderr = new StringBuffer()
    def outputReader = process.consumeProcessOutputStream(stdout)
    def errorReader = process.consumeProcessErrorStream(stderr)
    process.outputStream.withWriter('UTF-8') { writer -> writer.write(input) }
    if (!process.waitFor(20, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw new IllegalStateException('Local Git fixture timed out')
    }
    outputReader.join(); errorReader.join()
    [code:process.exitValue(), out:stdout.toString().replace('\r\n','\n'), err:stderr.toString()]
}
def override = { EnvVars vars, List settings ->
    settings.each { entry ->
        int equals = entry.indexOf('=')
        vars.override(entry.substring(0, equals), entry.substring(equals + 1))
    }
}
// 复现失败：Jenkins 将 VALUE_0= 删除，而不是传递一个空字符串。
def broken = new EnvVars(base)
override(broken, ['GIT_CONFIG_COUNT=1','GIT_CONFIG_KEY_0=credential.helper','GIT_CONFIG_VALUE_0='])
assert !broken.containsKey('GIT_CONFIG_VALUE_0')
def reproduced = run(['git.exe','config','--get','credential.helper'], broken)
assert reproduced.code == 128 && reproduced.err.contains('missing config value GIT_CONFIG_VALUE_0')

// 故意继承旧 indexed config，实际绑定必须覆盖它；退出后恢复原环境。
environment.putAll(['GIT_CONFIG_COUNT':'1','GIT_CONFIG_KEY_0':'credential.helper',
                   'GIT_CONFIG_VALUE_0':'!echo INHERITED_HELPER_MUST_NOT_RUN >&2; exit 97'])
def original = new EnvVars(environment)
def binding = new Binding([
    releaseJenkinsSecrets:[withPair:{ kind, first, second, action ->
        assert kind == 'git'
        environment.put(first, 'fixture-user')
        environment.put(second, 'fixture-password')
        try { action() } finally { environment.remove(first); environment.remove(second) }
    }],
    withEnv:{ settings, action ->
        def saved = new EnvVars(environment)
        override(environment, settings)
        try { action() } finally { environment.clear(); environment.putAll(saved) }
    },
    pwd:{ -> workspace.path },
    writeFile:{ args -> new File(workspace,args.file.toString()).setText(args.text.toString(),'UTF-8') },
    bat:{ command ->
        assert command.contains('del /q')
        ['.jenkins_git_askpass.cmd','.jenkins_git_askpass.ps1'].each {
            assert new File(workspace,it).delete()
        }
    }
])
def credentials = new GroovyShell(this.class.classLoader,binding).parse(
    new File(repo,'vars/releaseCredentials.groovy'))
credentials.withGitAuthentication {
    assert environment.GIT_CONFIG_COUNT == '0'
    assert run(['git.exe','init','.'],environment).code == 0
    // 配置文件和 indexed config 中的缓存 helper 都必须被末尾空 helper 禁用。
    def effective = run(['git.exe','config','--get','credential.helper'],environment)
    assert effective.code == 0 && effective.out == '\n'
    def input = 'protocol=https\nhost=fixture.invalid\n\n'
    [
        ['git.exe','credential','fill'],
        ['cmd.exe','/d','/c','git.exe credential fill']
    ].each { command ->
        def result = run(command,environment,input)
        assert result.code == 0
        assert result.out.contains('username=fixture-user') && result.out.contains('password=fixture-password')
        assert !result.err.contains('HELPER_MUST_NOT_RUN')
    }
}
assert environment == original
assert !new File(workspace,'.jenkins_git_askpass.cmd').exists()
try {
    credentials.withGitAuthentication { throw new IOException('fixture failure') }
    assert false
} catch (IOException expected) {}
assert environment == original
assert !new File(workspace,'.jenkins_git_askpass.ps1').exists()
println 'PASS Git environment: real Jenkins EnvVars reproduction, git init, empty helper, actual ASKPASS, child-process inheritance and failure cleanup; fixture accounts only, no network.'
println "Fixtures: $root"
