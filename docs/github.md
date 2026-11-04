# GitHub 与 Gitee 接入

仓库、正式维护目录、Jenkins 全局可信共享库及 Job 的库引用统一命名为 `july-jenkins-library`。

## 每台 Jenkins 的配置

- 维护目录：`D:/Jenkins/july-jenkins-library`。
- 全局可信共享库 Name：`july-jenkins-library`。
- 默认版本：`main`，或本机已经验收的固定提交/tag。
- Retrieval Method：Modern SCM；SCM：Git。
- 国内打包机 Repository：`https://gitee.com/xiao_someone/july-jenkins-library.git`，首次镜像同步成功后再切换。
- GitHub 主仓库：`https://github.com/XjVoilin/july-jenkins-library.git`，继续用于代码维护，也可作为直接读取源。
- 当前仓库可匿名读取；如果以后改为私有，需配置 SCM 读取凭据。流水线中的四条 Secret text 不会自动成为加载共享库的 SCM 凭据。

可信共享库可以调用 Jenkins 管理 API；应限制主仓库和镜像的写权限。库名、版本和加载机制参见 [Jenkins 官方共享库文档](https://www.jenkins.io/doc/book/pipeline/shared-libraries/)。

项目初始化器脚本示例：

```groovy
@Library('july-jenkins-library') _
unityMiniGameProjectJobs(
    buildFarmRoot: 'D:\\BuildFarm',
    agentLabel: 'unity',
    buildClass: 'July.Release.Editor.BuildPipelineCI',
    platforms: ['WeChat', 'TikTok']
)
```

新建的脚本式 Job 首次无参数运行会加载参数定义，并因必填参数为空而失败；刷新后通过参数化构建填写参数，再创建项目。

## GitHub → Gitee 自动同步

GitHub 为代码维护主仓库，Gitee 为单向镜像。工作流位于 `.github/workflows/mirror-to-gitee.yml`，在 `main` 更新、Tag 推送、手动运行时同步全部分支和 Tag；每天额外补同步一次。没有额外设置 job 或推送超时，不强推、不自动删除目标引用。镜像分叉时应先核对变更，不直接覆盖。

首次启用：

1. Gitee 创建空仓库 `xiao_someone/july-jenkins-library`，不初始化 README、许可证或 .gitignore。
2. 在本 GitHub 仓库 Settings → Secrets and variables → Actions → New repository secret 中添加 `GITEE_TOKEN`，值为 `xiao_someone` 账号可用于推送该镜像的 Gitee 私人令牌。若已有令牌具备目标仓库权限，可以复用其值；其他 GitHub 仓库的 repository Secret 不会自动共享到本仓库。令牌只填入 Secret，不写入源码或文档。
3. 合并工作流后检查首次运行。手动补同步入口：Actions → Mirror Jenkins library to Gitee → Run workflow，选择 `main`。
4. 确认两边的 `main` 和所需 Tag 指向相同提交后，再将 Jenkins 配置中的 Repository 切到 Gitee。Name 保留 `july-jenkins-library`，默认版本保留现有 `main` 或固定的已验收提交/Tag。仅切换 Repository 地址时不需要重建 Job 或修改 `@Library`。
5. 构建空闲时进行切换，再运行一个现有 Job，确认日志从 Gitee 加载预期提交。已运行的构建继续使用开始时加载的库版本。

`GITEE_TOKEN` 供 GitHub Actions 写入镜像；Jenkins 加载公开镜像可匿名读取，不需要这个写入令牌。使用私有镜像时，在 Jenkins 的共享库 SCM 配置中设置读取凭据。现有流水线的 Git/抖音全局 Secret text 与镜像认证是不同用途。

## 更换旧名称时

需要同时修改全局库配置和现有 Job 的 `@Library` 引用；仅更新 GitHub 中的模板不会自动重写已经创建的 Job。操作前备份配置、等待构建空闲，切换后验证现有 Job 和新创建的模板都引用新名字。无需删除重建项目，也无需修改 Source、AOT 或凭据。

旧目录保留作本地备份，不再作为新修改的发布源。不要将旧仓库历史合并或 mirror push 到 GitHub。

## 新打包机仍需准备

这是流程代码共享，不是整机环境复制。每台机器需要 Jenkins/Pipeline/Git/Folders/Active Choices/Credentials Binding/Pipeline Utility Steps 等插件、Git、Node、对应平台 CLI、Unity/Tuanjie、项目所需框架/发布工具，以及 `unity` 节点标签。

当前默认编辑器路径为 `D:\Install\UnityAll\Unity\Editor\2022.3.61t9\Editor\Tuanjie.exe`。直接调用构建流水线支持 `unityPath`，项目/版本初始化器尚未透传此项；不同安装路径需要后续补齐配置传递，不能假定会自动识别。当前文件访问模型仍为单台 Windows 机器，不支持直接跨机器 Controller/Agent 访问本地路径。

按 [全局账号配置](global-accounts.md) 在各 Jenkins 创建构建凭据，再填写各项目 Secrets。GitHub 不同步凭据、ReleaseSources、AOT、构建输出或机器配置。最后按 [验证步骤](validation.md) 验收。
