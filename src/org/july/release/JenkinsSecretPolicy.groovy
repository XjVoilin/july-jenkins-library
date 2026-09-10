package org.july.release
import com.cloudbees.groovy.cps.NonCPS
import com.cloudbees.hudson.plugins.folder.AbstractFolder
import com.cloudbees.hudson.plugins.folder.properties.FolderCredentialsProvider.FolderCredentialsProperty
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.CredentialsScope
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import jenkins.model.Jenkins

/** 仅检查凭证元数据；不把 Credentials/Secret 或私人值带回 CPS。 */
class JenkinsSecretPolicy {
    @NonCPS static List<String> ids(String kind) {
        switch(kind) {
            case 'git': return ['build-git-username','build-git-password']
            case 'douyin': return ['build-douyin-email','build-douyin-password']
            default: throw new IllegalArgumentException('未知全局构建凭证类型: '+kind)
        }
    }
    @NonCPS static void validate(List ids,List globalMetadata,List shadowingIds) {
        ids.each { id ->
            def matches=globalMetadata.findAll { it.id==id }
            if(matches.size()!=1 || matches[0].secretText!=true || matches[0].globalScope!=true)
                throw new IllegalStateException('缺少唯一的全局 Secret text 构建凭证: '+id)
            if(id in shadowingIds)
                throw new IllegalStateException('项目 Folder 存在同名凭证，会覆盖全局账号，拒绝执行: '+id)
        }
    }
    @NonCPS static void requireGlobal(String jobName,List ids) {
        def job=Jenkins.get().getItemByFullName(jobName)
        if(!job) throw new IllegalArgumentException('当前 Jenkins Job 不存在')
        def entries=SystemCredentialsProvider.getInstance().credentials
        def localIds=[]
        def parent=job.parent
        while(parent instanceof AbstractFolder) {
            def property=parent.properties.get(FolderCredentialsProperty)
            if(property) property.domainCredentialsMap.values().flatten().each { localIds << it.id }
            parent=parent.parent
        }
        validate(ids,entries.collect {
            [id:it.id,secretText:it instanceof StringCredentials,globalScope:it.scope==CredentialsScope.GLOBAL]
        },localIds)
    }
}
