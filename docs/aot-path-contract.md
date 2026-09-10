# Jenkins 与框架的 AOT 路径接口

本变更只修改 shared-library，不修改 com.july.release、项目包引用或真实构建数据。框架必须先实现并交付约定接口，再将此分支合入 Jenkins 使用的 master。旧框架可能忽略新参数，不能仅凭 Jenkins 离线测试通过就启用。

## 传参

全量构建在本次预留 Source 下传入：

```text
-aotBackupOutputPath "D:/BuildFarm/<项目>/ReleaseSources/<平台>/<核心版本>/<全量构建号>/aot"
```

Jenkins 只创建 Source 父目录，不预先创建 aot；若 aot 已存在，拒绝覆盖。关闭 BUILD_MINIGAME 的全量也传输出路径，但因为没有原包上传，不创建可供热更/CodeSplit 选择的 Source 状态。

热更构建传入：

```text
-aotBackupInputPath "D:/BuildFarm/<项目>/ReleaseSources/<平台>/<核心版本>/<所选全量构建号>/aot"
-aotBackupVersion <核心版本>
```

同时仍传递 -platform、-buildTarget MiniGame 和独立的 -planVersion。框架必须核对清单中的 CoreVersion、平台和 BuildTarget，不允许静默覆盖调用参数；已有工作副本不能替代指定输入。extraArgs 不得再次指定这三个 AOT 参数。

## 成功条件与职责

- 框架负责备份内容、内部清单格式、完整性校验、完成后发布最终目录，以及热更工作副本的替换恢复；指定路径失败不得使用其他来源。
- Jenkins 等待整个 Unity 命令成功退出后，检查指定 aot 是非空目录并记录整体目录指纹。不存在或为空即失败；不解析或虚构框架尚未交付的清单文件名/结构。
- 普通全量包快照及平台上传完成后，才将 Source 从 SNAPSHOTTED 更新为 SOURCE_READY。只有 AOT 目录或成功日志，不代表 Source 已可用。
- 热更前检查 Source 身份、版本、Git 提交继承关系、精确 AOT 路径和整体指纹；框架执行后再次确认输入快照未被修改。
- Jenkins 不再复制/恢复 HybridCLRData 内的 AOT，也不读取 ../AOTBackup。保留 git clean 的 HybridCLRData 缓存排除项不代表使用它兜底。

Jenkins 的整体指纹用于发现 Source 被修改；框架清单及逐文件校验用于证明 AOT 备份本身有效。二者用途不同。schemaVersion=6 是 Jenkins Source 元数据版本，不是框架备份格式版本。

## 启用顺序

1. 开发机完成框架接口并提交，项目更新到确实包含新接口的包版本；不要移动旧 tag。
2. 将项目变更合并到目标构建分支，确认打包机可以获取该版本。
3. 检查框架交付说明：指定目录只在备份完整后发布、输入严格校验、错误必须使整个 Unity 命令非零退出。若实际参数与本约定不同，先调整对接。
4. 在任务空闲时将此共享库分支合入 master；无需重建 Job。更新后的 Source 选择脚本可能需要 Jenkins 管理员审批。
5. 重新做全量构建，确认 aot 由框架生成，且没有额外的 AOTBackup/Workspace 归档；再选择该 Source 做热更和 CodeSplit 验证。

旧 schemaVersion=5 的 Source 不进入新版热更列表，手工提交旧选项也会失败；不原地转换或自动删除旧 Source。CodeSplit 的原包处理流程没有因本次 AOT 对接重写。

本分支不清理真实 AOTBackup、Source 或工作区。额外旧归档应在真实验收通过、确认不再使用后单独清理。
