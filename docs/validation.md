# 验证

## 自动化离线回归

在 PowerShell 执行（不调用 Jenkins Job、Unity、平台服务或通知）：

```powershell
& 'D:/Jenkins/july-jenkins-library/tests/run.ps1' `
  -Java 'D:/Install/JDK21/bin/java.exe' `
  -JenkinsHome 'D:/Jenkins' `
  -WarDir 'C:/Users/admin/AppData/Local/Jenkins/war' `
  -JenkinsWar 'C:/Program Files/Jenkins/Jenkins.war'
```

脚本直接使用本机已安装的 Jenkins/插件 JAR，不下载测试依赖。临时数据只放入系统 TEMP 的独立目录，输出位置供排查；测试数据不含真实凭证。

- compileAll：全部 vars 用真实 CPS 编译器编译，管理工具按普通 Groovy 编译。
- runtime：真实文件系统上的锁冲突、竞争、token 校验、跨项目隔离、Source 防覆盖、不可变字段、状态转换和检查点；两平台连续分包重试的平铺历史、周期/状态限制、已有上传或其他未确认操作的拒绝。
- parameterSchema：生成 XML 与运行时参数一致、默认值、固定参数和类型校验。
- projectJobs：生成真实 QA XML 并执行嵌入脚本（只捕获配置），验证固定版本、参数复用、XML/Groovy 转义、项目隔离和保留版本拦截。
- sourceChoices：合成的双项目、双平台、多版本/环境/状态，无 Job 页面上下文和 reactive 首次加载。
- codeSplitParameters：直接截取生产校验块，覆盖五种操作、两个平台以及 String/GString 回归。
- globalSecrets：全局凭据 ID/作用域/类型、Folder 同名冲突、绑定清理、禁止旧账号字段及项目配置入口校验。
- gitEnvironment：真实 Jenkins EnvVars 与本机 Git，复现空值环境变量删除问题；在隔离配置和临时目录中验证 git init、禁用缓存 helper、实际 ASKPASS、子进程继承及异常清理，仅使用虚拟账号，不访问远端。
- projectSecrets：执行实际目录初始化方法（模拟命令），验证四文件生成、相对路径、无 _comment、空密钥/COS 占位符拦截、YAML 错误脱敏、不覆盖已有项目及失败回滚。
- platformAdapters：真实适配器配合模拟命令，检查平台身份、robot 参数、CLI 成功标记及失败后的会话锁释放；确认两平台重试仍调用官方 dosplit，每次仅重建 release，微信来自 collection、抖音来自 raw，不调用 init/upload。
- cpsExecution：真实 CpsTransformer/Continuable，挂起/恢复、共享模块调用、检查点复用与异常清理；模拟正式分包挂起后中断、新执行再次挂起/恢复、尝试历史保存和 DONE 复用，上传中断仍保持未知状态。
- pipelineFlow：真实流水线阶段配合模拟 Jenkins/Unity/平台接口；76 次运行覆盖多项目两平台、全量/热更/分包、冲突及失败重试；包含 QA 固定分支/版本、拒绝参数覆盖版本、两平台各重复全量并分别保存 Source。上传回执与正式分包记录保留原成功任务；凭据未填写时不执行 Git/Unity、不预留 Source；覆盖显式 AOT 输入/输出、框架未输出/空输出/后续失败、输入篡改、旧/跨项目 Source 和参数覆盖拒绝。readJSON 模拟默认返回真实 JSON-lib 对象，仅 returnPojo=true 返回普通 Map/null；覆盖 null 统计再次采集、collect-2 已完成但状态更新失败的重试及第三轮采集，确认不重复远端准备或上传。新增双平台下载/校验/统计失败后的正式分包重试，验证残留清理、raw/aot/collection 不变、原采集记录不变；分包 DONE 后上传前中断可以复用、产物篡改拒绝、未知上传不会再次分包或上传。AOT 内容使用不透明的虚拟文件，不证明真实框架格式正确。

这些测试不等于真实 Jenkins Declarative 调度、Unity 构建或平台接口验收。

## Jenkins 只读检查

管理员在脚本控制台执行，版本改为实际存在的版本。不会修改 Job、审批脚本或触发构建：

```groovy
verification = [projectName:'GooseMarket', coreVersion:'1.6.0']
evaluate(new File('D:/Jenkins/july-jenkins-library/tools/verifyJenkins.groovy'))
```

检查热更和 CodeSplit 只有一份参数属性，Source 不依赖 jenkinsProject，首次加载和操作/平台切换均可计算。无可用 Source 是正常提示，读取失败不是。

## 真实验收顺序（需人工操作）

使用测试环境和测试平台账号。先确认项目 JSON 中 `feishu.enabled=false`，并检查 CDN 测试目录，避免覆盖线上。

1. 按 [AOT 对接顺序](aot-path-contract.md) 更新包含路径接口的框架及项目包引用，再启用共享库；新建测试项目 Folder，确认包含 CreateVersionJobs、CodeSplit、QA。QA 无版本输入框，平台选项与项目一致；准备 Tuanjie_Build/99.99.99 分支后再人工构建。创建正式版本 Job，确认不触发打包，也没有平台 AppID 等凭证参数；重复创建或使用保留版本 99.99.99 应报错。
2. 项目 A 全量 WeChat，再全量 TikTok，记录两个 Source 的平台、环境、Debug、Git commit、AOT 与指纹；确认互不改写。
3. 分别以两个 Source 做热更，检查继承正确，命令同时传入所选 aot 路径和核心版本断言；确认框架负责恢复，错误平台/项目 Source 不能混入。
4. WeChat Source：启动采集 → 真机采集 → 查看状态/刷新码 → 正式分包并上传 → 再次启动采集。核对状态、统计、上传版本和新一轮参考版本。
5. TikTok Source 按同样顺序验证；检查平台及项目 AppID、Source 和参考缓存不混用；Git/抖音构建账号按设计共用全局凭据。
6. 用项目 B 重复全量、热更及分包，确认 A 的 Source/参考缓存不变。跨项目尝试构造不存在或不匹配 Source 应失败。
7. 若需要验证真正并发，另行评估后增加 executor 或使用测试节点；本机只有一个 executor，顺序运行不能证明 Jenkins 实际并行隔离。不要为了测试直接修改生产执行器配置。
8. 故障重试只在测试环境做：正式分包普通失败后，排除网络/本地故障，再选择同一 Source 和“生成正式分包并上传”；检查日志提示重试、previousAttempts 保留旧任务、没有重复 init，产物校验通过后才上传。平台可能继续旧任务或生成新版本；本次审计的工具版本及行为见 [失败恢复](recovery.md)。上传结果未知仍必须先查平台，DONE 产物篡改仍必须拒绝。不要用断电/强杀真实发版任务来测试。

每次修改后重新运行离线回归；真实 Jenkins 页面交互、新模板创建、Unity 打包、平台真机采集和实际上传需另行验收，不能由离线测试通过推断。
