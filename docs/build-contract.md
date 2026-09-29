# Unity 构建与 Jenkins 发布接口

本接口需要配套 com.july.release 新源码。先发布不可变 UPM 包版本，更新两个项目引用并合入构建分支，再部署 shared library。旧包若未生成结果文件，将明确失败，不能默认为构建成功。

## 两端职责

- Unity：平台准备、AOT 生成/保存/校验/恢复、资源和平台包生成、COS/CDN 上传、报告真实产物位置。
- Jenkins：检出代码、凭证、Source 身份与原包归档、平台后台上传、Git 标签、代码分包和通知。
- CDN 项目根和 COS 项目根来自项目配置；平台 SDK、AppID/平台凭证属于项目。Jenkins 使用既有固定凭证注入位置 Tools/coscli/.cos.yaml，框架不提供第二份路径配置。
- 项目遵守框架的 ../Build、../AOTBackup、HotFix/AOTMeta 分组约定。Jenkins 仍通过结果读取实际包目录，不依赖 SDK 的 minigame/tt-minigame 导出规则，也不清理 Unity 导出目录。

## 调用

每次先调用一次 SyncPlatformDefines；框架判断完整宏集合并应用平台设置。必须等待该进程结束，再启动实际构建。不在 Groovy 中读取 ProjectSettings 的 WeixinMiniGame 行。

```text
Tuanjie.exe -batchmode -quit -nographics -projectPath "D:/BuildFarm/MableSorting/Workspace" -buildTarget MiniGame -executeMethod July.Release.Editor.BuildPipelineCI.SyncPlatformDefines -platform WeChat

Tuanjie.exe -batchmode -quit -nographics -projectPath "D:/BuildFarm/MableSorting/Workspace" -buildTarget MiniGame -executeMethod July.Release.Editor.BuildPipelineCI.FullBuild -platform WeChat -env Dev -planVersion 0.0.1 -miniGame -uploadCdn -aotBackupOutputPath "D:/BuildFarm/MableSorting/ReleaseSources/WeChat/0.0.1/1/aot"

Tuanjie.exe -batchmode -quit -nographics -projectPath "D:/BuildFarm/MableSorting/Workspace" -buildTarget MiniGame -executeMethod July.Release.Editor.BuildPipelineCI.HotUpdateBuild -platform WeChat -env Dev -planVersion 0.0.2 -uploadCdn -aotBackupInputPath "D:/BuildFarm/MableSorting/ReleaseSources/WeChat/0.0.1/1/aot" -aotBackupVersion 0.0.1
```

Debug 两次调用均加 -debug。FORCE_REBUILD 勾选时实际构建加 -forceRebuild，默认每次关闭。99.99.99 跳过发布版本标签 Guard，框架也允许重复上传；禁止覆盖已有 Source、AOT 或移动已有 Git 标签的规则继续生效。

## 结果与发布顺序

Unity 写工作区 release-build-result.json，schemaVersion=1。Jenkins 在调用前也删除旧结果，以便旧框架或参数失败不能遗留伪成功。读取时必须核对身份字段、AOT 路径、succeeded、cdnUploaded 和需要的平台包目录；Unity 退出码为 0 并不单独构成成功。

全量：Unity 成功 → AOT 校验 → 原包 SNAPSHOTTED → full-upload 回执 DONE → Git 标签 → SOURCE_READY。
热更或关闭 BUILD_MINIGAME 的资源全量：Unity 成功 → AOT 校验 → Git 标签；不创建可选的原包 Source。

Git 标签规则仍为 release/{env}/{platform}/{core}/{plan}，主标签存在时使用 +full-b{构建号} / +hot-b{构建号}。完整计划含原提交，保存在 Source.releaseTag 或归档的 release-publication.json。标签补发核对远端 peeled commit；已推送到相同提交视为成功，不同提交拒绝覆盖。任何模式不 force push。

保存 build.log、sync_defines.log、release-build-result.json、release-publication.json。AOT 格式和唯一输入规则保持原接口，Jenkins 不解释框架的 AOT 内部清单。

## 只补齐发布，不重复构建或上传

全量平台上传回执 DONE 后，即使标签或 SOURCE_READY 写入失败，也不要重跑 FullBuild。使用一次性的管理员维护 Pipeline，在项目锁下、正确 Git 仓库工作区和凭证作用域中调用 completeFull。以下示例不会调用 Unity 或平台上传：

```groovy
@Library('july-jenkins-library') _
import org.july.release.ReleaseStorage
node('unity') {
    ws('D:/BuildFarm/MableSorting/Workspace') {
        def lease = ReleaseStorage.acquire(new File('D:/BuildFarm/MableSorting'), env.BUILD_URL)
        try {
            // 先核对此工作区 origin 指向 MableSorting，且 Git 对象包含原 Source 提交。
            releaseCredentials.withGitAuthentication {
                releaseGitTag.completeFull('D:/BuildFarm/MableSorting/ReleaseSources/WeChat/0.0.1/1/code-split-state.json')
            }
        } finally { ReleaseStorage.release(lease) }
    }
}
```

completeFull 检查原包和 AOT 指纹及 DONE 回执，补推标签并将 Source 标记就绪；保留原上传任务号和 URL。热更/资源全量可下载该成功 Unity 任务归档的 release-publication.json，在同样的项目锁、工作区和 Git 凭证作用域内调用 releaseGitTag.completeResources('release-publication.json')。

上传仍为 STARTED 时，远端结果可能未知，completeFull 会拒绝。先按 recovery.md 核对平台结果，不能仅将状态改成 DONE，也不能自动重新上传。该接口不声称实现了跨 CDN、平台服务和 Git 的原子事务。

## 验证边界

离线模拟验证结果接口、Source 不可变性、上传回执和补发流程；本地 bare Git 验证失败重试、重复调用和禁止移动标签。真实 Jenkins/插件版本、团结平台导出、COS、后台配置预请求与平台上传仍需在测试环境联调。
