def repo = new File(args[0])
def temp = File.createTempDir('login-diagnostics-', '')
def messages = []
def removed = []
def password = 'fixture-password-123'
def email = 'fixture@example.invalid'
def fixture = new File(temp, 'fixture.cmd')
def binding = new Binding([
    pwd: { settings -> assert settings.tmp; temp.path },
    echo: { text -> messages << text.toString() },
    error: { text -> throw new IllegalStateException(text.toString()) },
    writeFile: { settings ->
        def file = new File(settings.file.toString())
        file.parentFile.mkdirs()
        file.setText(settings.text.toString(), settings.encoding)
    },
    readFile: { settings -> new File(settings.file.toString()).getText(settings.encoding) },
    dir: { path, body ->
        def directory = new File(path.toString()).canonicalFile
        assert directory.parentFile == temp.canonicalFile && directory.name.startsWith('cli-')
        body()
        assert directory.deleteDir()
        removed << directory
    },
    deleteDir: { -> },
    bat: { settings ->
        assert settings.returnStatus && !settings.returnStdout
        assert settings.script.toString().contains('2>&1')
        def wrapper = new File(temp, 'wrapper.cmd')
        wrapper.text = settings.script.toString() + '\r\n'
        def process = new ProcessBuilder('cmd.exe', '/d', '/c', wrapper.path).start()
        process.consumeProcessOutput(new StringBuffer(), new StringBuffer())
        process.waitFor()
    }
])
def script = new GroovyShell(this.class.classLoader, binding).parse(new File(repo, 'vars/releaseFiles.groovy'))
def command = '"' + fixture.path + '"'
def run = { String body, String expectedError ->
    messages.clear()
    fixture.text = '@echo off\r\n' + body + '\r\n'
    try {
        def output = script.runDiagnosedBat('fixture login', command, [email, password])
        assert expectedError == null : 'Expected failure: ' + expectedError
        return output
    } catch (IllegalStateException failure) {
        assert expectedError && failure.message.contains(expectedError) : failure.message
    } finally {
        assert removed && !removed.last().exists()
        assert !messages.join('\n').contains(password)
        assert !messages.join('\n').contains(email)
    }
}
assert run('echo Login success\r\nexit /b 0', null) == 'Login success'
run('echo stdout ' + password + '\r\necho stderr ' + email + ' 1>&2\r\nexit /b -1073740791', '-1073740791 (0xC0000409)')
assert messages.any { it.contains('stdout ****') && it.contains('stderr ****') }
assert messages.any { it.contains('elapsedMs=') && it.contains('0xC0000409') }
run('exit /b -1073740791', '-1073740791')
assert messages.any { it.contains('CLI 未输出任何内容') }
run('echo Error: fixture failure\r\nexit /b 0', 'CLI 输出包含失败信息')
assert removed.size() == 4
println 'PASS login diagnostics: real cmd stdout/stderr and crash exit; redaction; empty output; zero-exit error; temporary log cleanup'