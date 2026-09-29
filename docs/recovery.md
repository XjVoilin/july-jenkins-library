# 失败恢复

不要看到 `.jenkins-project.lock` 或 STARTED 就直接删除。正式分包普通失败按下面的正常重试流程处理；采集、上传及残留锁仍需核对。管理员恢复工具默认只读，所有写操作要求 Jenkins 全部构建空闲、明确理由和预期 owner/token。

## 正式分包失败：直接重试原操作

网络或本地故障排除后，刷新 CodeSplit 参数页，选择原平台、原 Source 和“生成正式分包并上传”再次构建。无需新增 Job、操作选项、修改 JSON 或删除检查点。

- finalize-当前周期 为 STARTED，且本周期没有上传记录、没有其他 STARTED：保留旧尝试的 owner/时间等信息到 previousAttempts，再从干净副本执行官方 dosplit。只替换本 Source 的 release 工作产物，不清理 raw、aot、collection 或其他 Source；失败构建的 Jenkins 日志继续保留。
- finalize 为 DONE：验证保存的产物指纹后复用，继续上传，不重新分包；文件改变则停止，不自动覆盖。
- upload 为 STARTED：仍停止，先核对平台；不能用重跑分包绕过不确定的上传。
- collect 为 STARTED、其他周期有未确认操作或项目/抖音会话锁残留：仍需先核对，不能抢锁或重置采集。

该重试允许平台继续旧任务或产生新的未上传版本，不承诺固定重用某个远端版本，也不会撤销旧版本。只在用户再次构建时尝试一次；网络仍失败就再次报错，不循环重试。

本机审计依据：wasmsplit-ci 1.1.33 的 dosplit 在初始化时核对远端子版本，按状态启动任务、查询或下载；tt-wasmsplit-ci 1.0.6 在状态 9 启动分包、4 等待、5 下载。共享库保持官方命令不变，不修改/拦截 CLI，也不依赖私有下载接口。平台工具升级后需要重新验证这些行为。

## 先检查

在 Jenkins 管理员脚本控制台：

```groovy
recovery = [projectName:'GooseMarket', platform:'WeChat', coreVersion:'1.6.0', sourceBuild:'4']
evaluate(new File('D:/Jenkins/july-jenkins-library/tools/recover.groovy'))
```

只查项目锁时可省略 platform/coreVersion/sourceBuild；查机器级抖音会话锁时加 `scope:'tiktok-session'`。输出 owner、token 及 Source 检查点，不读取凭证。

## 释放确认残留的锁

先确认 owner 对应构建已经结束，不能仅根据锁存在时间判断。把只读检查得到的值填入：

```groovy
recovery = [projectName:'GooseMarket', scope:'project',
  action:'release-stale-lock',
  expectedOwner:'填写检查输出的 owner', expectedToken:'填写检查输出的 token',
  reason:'已确认对应构建终止，且无进程继续访问项目']
evaluate(new File('D:/Jenkins/july-jenkins-library/tools/recover.groovy'))
```

抖音 CLI 会话残留锁改为 `scope:'tiktok-session'`，还要确认没有遗留 tmg/tt-wasmsplit-ci 进程。工具在锁目录保存恢复备份，校验路径、owner、token 后才释放。

## 允许一个未生效操作重试

仅适用于已确认远端没有生效的 STARTED 操作。先释放确认残留的项目锁，再执行：

```groovy
recovery = [projectName:'GooseMarket', platform:'WeChat', coreVersion:'1.6.0', sourceBuild:'4',
  action:'retry-unapplied-operation', operation:'upload-1',
  expectedOwner:'填写操作记录中的 owner', confirmNoRemoteEffect:true,
  reason:'已核对平台没有本次上传，允许重试']
evaluate(new File('D:/Jenkins/july-jenkins-library/tools/recover.groovy'))
```

操作名必须从检查输出复制，例如 collect-1、finalize-1、upload-1。工具获取项目锁、备份完整状态，删除指定 STARTED 标记并追加管理员恢复记录，不修改 Source 基线，不触发构建。

如果远端已经成功或结果无法确认，不使用此动作，也不把 confirmNoRemoteEffect 设为 true 试错。需要核对实际平台版本/产物再进行针对性对账；工具故意不提供“一键当作成功”。

## 再次采集已完成，但保存状态失败

如果日志显示采集包生成成功，且 `operations.collect-<下一轮>.status` 已为 `DONE`，但 Source 仍为上一轮的 `UPLOADED`，不要删除检查点或重新全量。先修复本地失败原因并核对采集目录仍保留本次产物，再使用同一个 Source 重试“再次启动代码分包采集”。流水线会打印“复用已完成步骤: collect-<下一轮>”，跳过远端准备并继续保存状态、查询统计及生成采集码。

JSONNull 导致的 StackOverflowError 已通过统一 `readJSON returnPojo: true` 修复；不会迁移或修改已有 Source。所有写入状态的 JSON 数据应使用普通 Map/List/null，不要把 JSON-lib 对象传给 JsonOutput。

## Source 目录冲突

同一项目/平台/核心版本/全量构建号只能创建一次 Source。即使前次失败留下空预留目录，也不自动覆盖。用新的 Jenkins 构建号重建；不要为了继续打包删除已经存在的正式基线。

全量平台上传已 DONE、标签或 Source 就绪记录失败时，使用 [仅补齐发布](build-contract.md#只补齐发布不重复构建或上传)。不要重跑 FullBuild 或修改快照；STARTED 上传仍需先核对远端。
