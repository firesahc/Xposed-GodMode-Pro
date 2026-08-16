# Xposed-GodMode 稳定版本上下文

本上下文统一 6.8 兼容稳定线中规则效果、验收证据和发布状态的用语，避免把匹配结果、运行时所有权和设备验收混为一谈。

## RuleRecord 规则定义边界

`RuleRecord` 是兼容聚合对象，不是所有规则字段的长期职责所有者。它负责承载
v6.9 的扁平 JSON/ZIP V1 和 Parcelable 兼容边界，并组合以下不可变组件：

- `MatchSpec`：规则如何定位目标，包含 Activity、View、路径、文本和匹配模式等匹配定义；
- `RuleEffect`：规则对目标施加什么效果，按 `RemoveEffect` 和 `ModifyEffect` 区分；
- 其余字段：版本、别名、预览图和采集时的原始信息，暂属于兼容/展示数据，不作为运行时身份。

JSON 和 Parcel 的外部布局仍保持旧版扁平形式。组件只在进程内部提供单一的稳定字段所有者，
不得在 `RuleRecord` 中保留同一稳定字段的扁平影子。

`RuleSlotKey` 是由权威包范围和 `MatchSpec` 派生的运行时槽位身份，不持久化到规则文件，
也不作为规则效果或 View 基线的替代物。`RuleDraft` 是 Editor 的可变草稿，只负责构造新
`RuleRecord`；Activity owner、generation、Bitmap、SAVING 状态和异步回调仍由 Editor 会话管理。

`AppliedState` 记录一次实际接管的宿主 View 基线和属性所有权。它与 `MatchSpec`、
`RuleEffect`、`RuleSlotKey` 相互独立：规则定义描述“应做什么”，AppliedState 描述“本次
运行时实际接管了什么”。

稳定迁移禁止以下做法：通过完整 `RuleRecord` 复制反复生成匹配/效果小表；让 UI 展示字段
决定 Runtime diff；将 `RuleSlotKey`、AppliedState 或编辑会话状态写入 Parcelable/备份格式。

## 规则效果

**匹配身份**：
规则用于定位宿主目标的稳定特征；它描述目标是谁，不描述规则当前写入了什么。
_Avoid_: 当前 View 状态、效果内容

**应用目标**：
某条规则在一次 Activity 生命周期中实际取得并写入效果的宿主目标。规则生效后，即使目标状态不再满足原匹配条件，它仍然是该效果的撤销对象。
_Avoid_: 再匹配目标、全局绑定

**效果基线**：
规则首次接管属性前的宿主状态，是撤销当前规则效果时可恢复的边界。
_Avoid_: 最近状态、规则原始字段

**效果所有权**：
规则对自己实际写入且宿主尚未接管的属性所持有的恢复权；宿主后续主动改变的属性不再属于该规则。
_Avoid_: 强制回滚、完整 View 快照

## 验收与发布

**稳定合同**：
6.9 稳定线必须保持的 6.8 外部兼容边界，包括规则和备份格式、IPC/Parcelable、应用身份及生产模块拓扑。
_Avoid_: 7.0 current 合同

**候选源码**：
版本冻结后用于构建全部验收产物的唯一源码提交；构建输入变化即产生新的候选源码。
_Avoid_: 证据提交、任意开发提交

**产品失败**：
测试完成并给出业务断言、未捕获异常或可归因于产品代码的失败终态。
_Avoid_: 进程中断、宿主未启动

**基础设施中断**：
测试没有取得业务终态，且仅观察到宿主未启动、runner 被系统终止或超时。它既不是通过，也不能证明产品失败。
_Avoid_: 用例失败、设备通过

**Instrumentation 通过**：
测试 runner 在指定构建上执行完成并取得明确通过终态。它证明被执行的测试行为，不等同于 LSPosed、Binder、备份或完整设备发布验收。
_Avoid_: AndroidTest 编译通过、DEVICE 通过

**设备发布验收**：
同一候选源码的模块、测试 APK 和设备身份绑定后，对 LSPosed 注入、真实宿主交互、Binder 恢复、备份恢复及重启行为完成的验收。
_Avoid_: 单模块 instrumentation、开发阶段设备探针
