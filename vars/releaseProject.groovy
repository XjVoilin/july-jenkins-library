import org.july.release.ReleaseStorage
def load(Map config) {
    def name = config.projectName?.toString()
    if (!(name ==~ /[A-Za-z0-9][A-Za-z0-9_.-]*/)) error 'projectName 非法'
    def farm = new File((config.buildFarmRoot ?: 'D:\\BuildFarm').toString()).canonicalFile
    def root = ReleaseStorage.inside(farm, new File(farm, name))
    def secrets = new File(root, 'Secrets')
    def credentials = ReleaseStorage.inside(secrets, new File((config.credentialsFile ?:
        new File(secrets, 'credentials.local.json').path).toString()))
    def data = ReleaseStorage.read(credentials)
    def forbidden = ['git':['username','password','usernameCredentialsId','passwordCredentialsId'],
                     'douyin':['email','password','emailCredentialsId','passwordCredentialsId']]
    forbidden.each { section, keys ->
        if (data[section] instanceof Map && keys.any { data[section].containsKey(it) })
            error '项目 JSON 禁止保留 Git/抖音账号密码或凭证 ID；请迁移到统一的全局 Secret text'
    }
    if (!(data.feishu instanceof Map) || !(data.feishu.enabled instanceof Boolean)) {
        error '项目凭证字段 feishu.enabled 必须明确为 true 或 false'
    }
    return [farm:farm, root:root, workspace:new File(root,'Workspace').path,
            build:new File(root,'Build').path, sources:new File(root,'ReleaseSources').path,
            splitCache:new File(root,'Cache/CodeSplitRefs').path,
            secrets:secrets.canonicalFile, credentialsFile:credentials, credentials:data]
}
