# Unity 小游戏 Jenkins 共享库

面向 Windows 单台打包机、多个 Unity/Tuanjie 项目。使用项目 Folder + 项目级 BuildFarm；Git/抖音账号统一使用全局 Secret text。不兼容旧扁平 Job 或项目 JSON 中的明文账号。

## 日常入口

- 项目初始化器调用 `unityMiniGameProjectJobs`：创建项目 Folder、`CreateVersionJobs`、`CodeSplit`、`QA` 和项目四文件配置骨架（JSON、两个空微信密钥、COS 模板），不打包。
- `<项目>/CreateVersionJobs`：输入一次核心版本，使用仓库专用模板创建 `<版本>_FullBuild` 和 `<版本>_HotUpdate`，拒绝覆盖已有 Job。
- `<项目>/QA`：固定版本 `99.99.99`、分支 `Tuanjie_Build/99.99.99`，复用全量构建流程和参数；不提供版本输入框，不创建 QA 热更 Job。平台、环境、Debug、缓存清理、强制重建及小游戏出包保持可选。平台上传、Git Tag、Source 归档遵循普通全量完成顺序，并非 Unity 面板的临时 QA 模式；99.99.99 允许同版本重复发布，不需要 FORCE_REBUILD；已有 Source 快照仍禁止覆盖。
- `99.99.99` 保留给 QA，版本创建器拒绝创建同版本 Job，避免两个 Job 的构建号冲突。新项目仓库需自行准备 QA 分支；初始化器不会创建 Git 分支。已有项目不会自动补建 QA，也不要为此删除重建项目。
- FullBuild：构建普通全量包，保存独立 Source；HotUpdate：从所选 Source 继承平台、环境、Debug 和 AOT；CodeSplit：围绕所选 Source 采集、正式分包、上传。
- Git/抖音账号使用四个固定的 Jenkins 全局 Secret text。项目 JSON 只保留项目专属配置与其他凭证；缺失直接失败，无账号回退。关闭通知设 `feishu.enabled=false`。参见 [全局账号配置](docs/global-accounts.md)。

## 目录与模块

```text
D:/BuildFarm/
  <项目>/
    Workspace/              项目工作区（全量和热更共享）
    Build/<平台>/<核心版本>/  可重建的构建输出
    ReleaseSources/<平台>/<核心版本>/<全量构建号>/
    Cache/CodeSplitRefs/<平台>/
    Secrets/                项目本地 JSON、密钥及 COS 配置，不入库
  ToolSessions/TikTok/       机器级抖音 CLI 登录会话锁
```

Source 的环境及 Debug 存在状态文件中，不以目录中的环境层级区分。一个 Source 必须绑定唯一全量构建号；平台、版本和构建号都相同时，禁止覆盖，即使勾选 FORCE_REBUILD。重建 Job 后构建号从头开始也不能覆盖原 Source，应使用新的构建号。

| 模块 | 职责 |
| --- | --- |
| unityMiniGamePipeline / codeSplitPipeline | 阶段编排，不重复实现通用工具 |
| releaseProject / releaseCredentials | 项目路径、项目专属配置、Git ASKPASS 与文件凭证 |
| releaseJenkinsSecrets / JenkinsSecretPolicy | 全局 Secret text 固定 ID、作用域检查与运行时绑定 |
| releaseParameters | Job XML 与运行时参数的唯一规则、校验及默认值保存 |
| releaseFiles | 原包/分包文件同步、路径检查与目录指纹 |
| releaseWeChat / releaseTikTok | 两个平台的上传、采集、预览及正式分包命令 |
| releaseSplitMetrics / releaseNotifications | 统计解析、参考版本及飞书通知 |
| releaseUnityResult / releaseGitTag | Unity 结果契约、发布标签和仅补齐发布记录 |
| releaseState / ReleaseStorage | 项目锁、Source/AOT 路径绑定、原子状态写入、操作检查点 |
| TemplateText | 非 CPS 的模板转义和替换 |

## AOT 职责

Jenkins 全量传入 -aotBackupOutputPath，热更传入 -aotBackupInputPath 和 -aotBackupVersion。com.july.release 负责 AOT 内容、发布、恢复及校验；Jenkins 不读取框架工作目录，只记录指定快照的整体指纹。新 Source 使用 schemaVersion=6，旧 Source 不作为热更基线。详见 [AOT 接口与启用顺序](docs/aot-path-contract.md)。

## 并发与重试规则

同一 Job 使用 Jenkins 的禁止并发配置；不同 Job 访问同一项目时再获取项目锁，范围覆盖工作区、AOT、缓存、Source 与上传。不同项目相互独立，但抖音命令会额外获取机器级会话锁，避免登录账号交叉覆盖。

锁采用文件原子创建，冲突时明确失败，不排队、不抢锁，也不根据超时自动删除。正常结束和普通失败在 cleanup/finally 释放；进程被强杀等情况下可能留锁。当前方案要求 Jenkins Controller 和构建节点能访问同一台 Windows 机器的上述绝对路径；不支持远程 Agent/分布式共享盘，不需要新增锁插件。

CodeSplit 的采集、正式分包和上传分别记录 STARTED/DONE 检查点。DONE 可复用结果：例如上传成功后参考版本写入失败，重试不会再次 dosplit/upload，上传记录仍指向原成功任务。正式分包文件指纹改变则拒绝复用。

正式分包的 STARTED 可以在下一次构建重试：选择同一 Source 和“生成正式分包并上传”即可。仅在当前采集周期、没有本周期上传记录且没有其他未确认操作时允许；旧尝试保存在 previousAttempts。微信从 collection、抖音从 raw 重新镜像干净的 release，再运行官方 dosplit，平台工具按当前状态继续查询、下载或生成新版本。不保证沿用上次远端版本，也不撤销旧远端结果；不重启采集、不修改 Source/AOT 基线。不会在同一次构建内无限重试。

采集和上传的 STARTED 仍需先核对远端，不能直接重做。这不是与远端平台之间的原子事务：尤其上传可能已成功而本地 DONE 尚未落盘，必须保守拦截。全量/热更的 Unity 内部 CDN 上传也不在这套 CodeSplit 检查点保证范围内。

## 验证与运维

参见 [验证步骤](docs/validation.md) 和 [失败恢复](docs/recovery.md)。

共享库名称统一为 `july-jenkins-library`，Job 使用 `@Library('july-jenkins-library') _`。Jenkins 的 Modern SCM / Git 地址为 `https://github.com/XjVoilin/july-jenkins-library.git`，默认版本为 `main`（正式环境也可固定验收后的提交或 tag）。新构建从 GitHub 加载；本地修改需提交并推送，已经运行的构建不会中途切换库版本。

正式维护目录使用 `D:/Jenkins/july-jenkins-library`。该目录用于修改、测试、提交，以及运行管理员工具，不要求 Jenkins 从本地目录加载。多台独立打包机可以使用同一 GitHub 仓库，但各自配置工具、凭据和 BuildFarm。详见 [GitHub 接入](docs/github.md)。

已有项目/版本 Job 不需要重建。运行时会同步参数定义，新建 Job 使用同一份定义生成 XML。涉及新项目 Source 脚本时仍可能需要管理员审批；不自动批准所有脚本。

长期运维工具仅保留 tools/verifyJenkins.groovy（只读 Source 验收）和 tools/recover.groovy（显式失败恢复）。全量、热更和 CodeSplit 共用 pipeline-job.xml.tpl，各自参数仍由 releaseParameters 生成；版本 Job 创建器保留独立参数模板。

维护前应在构建空闲时记录当前可用提交，并按需要备份配置及状态。回退前核对目标代码与当前凭据结构、Source 状态格式是否一致；不得直接回退到依赖已删除旧凭据或 JSON 明文账号的历史版本。存在 STARTED 操作或项目锁时先按恢复文档处理，不做自动数据降级或旧格式兼容。

Unity 平台准备、产物结果、CDN 与平台上传分工及发布补发说明见 [构建接口](docs/build-contract.md)。
