import org.july.release.JenkinsSecretPolicy

def check(String kind) {
    JenkinsSecretPolicy.requireGlobal(env.JOB_NAME?.toString(),JenkinsSecretPolicy.ids(kind))
}
def withPair(String kind,String firstVariable,String secondVariable,Closure action) {
    check(kind)
    def ids=JenkinsSecretPolicy.ids(kind)
    withCredentials([
        string(credentialsId:ids[0],variable:firstVariable),
        string(credentialsId:ids[1],variable:secondVariable)
    ]) { action() }
}
