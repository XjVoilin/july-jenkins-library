# 全局 Git / 抖音账号

这四项属于统一构建账号，所有使用本共享库的可信项目共用，不按项目隔离：

| Jenkins Secret text ID | 内容 |
| --- | --- |
| build-git-username | Git 登录用户名 |
| build-git-password | Git 密码或访问令牌 |
| build-douyin-email | 抖音登录邮箱 |
| build-douyin-password | 抖音密码 |

在 Jenkins 凭据管理的 System 存储、全局域中创建，凭证类型选 Secret text，作用域选 Global（不是 System）。这里的 System 存储与 System 作用域不是一回事。

采用四条 Secret text 是为了让用户名、邮箱也进入加密 Secret 字段；普通“用户名和密码”类型中的用户名字段不是加密字段。账号值不写入 Job 参数、项目 JSON 或共享库代码，ID 在 JenkinsSecretPolicy 中集中维护。项目 Folder 不得再创建这四个同名 ID，避免继承查找覆盖全局账号。

## 运行规则

- Git 拉取及 Unity 的同步宏、构建、打标签/推送子进程均在绑定范围内。Git credential.helper 在该子进程环境中清空，不回退 Windows 缓存账号，不修改机器的全局 Git 配置。
- 空 helper 通过非空的 GIT_CONFIG_PARAMETERS 字符串表达，GIT_CONFIG_COUNT=0 屏蔽继承的 indexed config。不能使用 GIT_CONFIG_VALUE_0= 表达空 helper：Jenkins 会直接删除空值环境变量，导致 Git 报缺少配置值。设置只在凭据绑定范围内生效，退出后恢复。
- 抖音每个 CLI 会话都显式使用全局邮箱和密码登录，仍保留机器级登录锁；不同项目 AppID、Source、缓存和 CDN 路径仍独立。
- 缺少凭证、类型/作用域错误、同名项目凭证、Git/平台操作权限失败时直接报错，不换账号兜底。
- Jenkins withCredentials 对四个值做日志遮罩。进程执行期间仍需要环境变量/内存中的值，抖音 CLI 仍使用其自身登录状态文件；这不是操作系统层面的秘密隔离。
- 有权修改并执行这些流水线的人应属于同一可信范围；不要将不可信项目加入共用账号流程。

项目 JSON 不再有 git 账号段，douyin 只保留 appId。微信密钥、COS 和飞书配置保持原来的项目独立方式。通知开关仍是 feishu.enabled。

## 新 Jenkins 首次配置

1. 管理员在 Jenkins 凭据管理的 System 存储、全局域中创建上表四项凭据：类型为 Secret text，作用域为 Global，ID 必须完全一致。
2. 配置共享库和项目初始化器，再创建项目 Folder。项目 JSON 只填写项目专属配置，不填写 Git/抖音账号，也不填写这四项凭据的 ID。
3. 确认构建账号具备目标 Git 仓库和抖音应用权限，再按 [验证步骤](validation.md) 验收。本地回归不证明远端账号权限。

同一 Jenkins 添加后续项目时，直接复用现有四项全局凭据，无需重复创建或导入。删除项目 Folder 不会删除全局凭据。

## 更新构建账号

停止提交新构建，并等待运行中、排队任务结束；在凭据管理中修改对应 Secret text 的值，保持 ID 不变。用户名/密码、邮箱/密码成对更新完成后再恢复构建，验证实际权限。更新会影响所有使用本共享库的项目，不需要修改各项目 JSON 或 Job。

## 项目专属配置

初始化器一次创建四个文件，不生成或继承其他项目的真实凭证：

- credentials.local.json：不含 _comment；填写微信/抖音 AppID，三个文件路径已预填相对路径。微信 robot 默认上传 1、预览 2；飞书默认关闭。
- wechat-upload.key：空文件，粘贴该项目的微信上传密钥完整内容。
- wechat-wasm-split.key：空文件，粘贴该项目的微信分包密钥完整内容。
- cos.yaml：填写所有 __REQUIRED__ 占位符。secretid、secretkey 和存储桶 name 必填；endpoint 或 region 至少填写一个，若只用 region，需将 endpoint 占位符改为空字符串。protocol 默认 https；sessiontoken 用临时凭据时填写，alias 按项目上传配置需要填写。

这些文件均位于本项目 Secrets 内，不提交 Git。JSON 路径无需手动修改，除非主动更换文件名。不通知时保持 feishu.enabled=false。

空白或仅含 BOM 的密钥文件、残留占位符、错误 YAML 和缺失 COS 必填项都会明确报错，不输出文件内容。全量/热更在 Git 操作前检查 COS；微信上传/分包按实际操作检查相应密钥，无需为未使用的平台填凭据。这些检查不替代实际平台权限或密钥有效性验证。

不支持旧明文账号字段或项目级账号凭据 ID。旧账号导入与清理是已完成的一次性维护，相关脚本不再保留在当前版本，也不属于新项目初始化流程。不要恢复含账号明文的旧 JSON。

Jenkins 凭据和项目 JSON 的维护不会自动清理 Windows 凭据管理器、第三方 CLI 登录缓存、旧构建日志或磁盘备份软件中的历史数据。
