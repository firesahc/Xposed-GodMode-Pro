# Xposed-GodMode 文档导航

本文档目录是当前架构和验证合同的入口。代码、测试或旧审计结论与这里的
合同不一致时，先更新对应的符合性记录，再决定是否修改实现。

## 当前合同

- [6.10 IPC 与权威边界](6.10-ipc-authority.md)：当前 `6.10.0` 的唯一跨进程
  合同。system_server 是规则、图片、配置和日志的写入权威；客户端只通过
  `IRuleService` 访问它。
- [ADR-0002：6.10 IPC 与写入权威](adr/0002-6-10-ipc-authority.md)：记录
  descriptor 硬切、租约、快照校验、持久化顺序和恢复语义。
- [ADR-0001：RuleRecord 内部组件](adr/0001-rule-record-wire-components.md)：
  记录 RuleRecord 的内部拆分，但不改变 JSON、Parcelable、ZIP V1 或匹配结果。
- [全项目设备测试规则](device-test-rules.md)：规定所有版本和测试目标的前台
  Activity 唤起、持久化日志目录核对以及设备证据记录顺序。

## 历史合同

- [6.9 稳定版本台账](6.9-stabilization.md) 只描述 `codex/6.9-stabilization`
  的历史基线和证据，不约束当前 `6.10.0` 的 IPC。文中出现的“旧 AIDL 冻结”
  仅表示 6.9 发布时的合同；6.10 已删除旧 AIDL，不提供双协议 fallback。

## 验证

`scripts/verify-stabilization.ps1` 是静态合同门禁，检查生产代码没有旧 AIDL、
裸 SharedMemory 快照、Binder Bitmap 传输、世界可写权限或 7.0 规则模型。
它不能替代真实设备验证。发布候选还必须保留 JVM、AndroidTest、注入、Binder
死亡重连、SELinux/owner、ROM Hook、Recycler 快速复用和图片迟到回调证据。

libxservicemanager 子模块指针自 `abd9f62` 起恢复常规更新，门禁不再将其
与 v6.8.0 基线比较（该断言在分叉后恒红已无守门价值）；子模块变更仍随
主仓库提交统一评审。

## 不变项

6.10 不迁移规则，也不引入新的规则语义。以下内容必须由 golden 测试锁定：

- RuleRecord 扁平 JSON 字段和 Parcelable 槽位；
- ZIP V1 备份布局和现有图片路径；
- 当前 matcher 的匹配结果以及 effect 的应用/撤销含义。

## 编辑会话

6.10 的编辑开关是全局许可，不采用单目标会话。开启后，各注入目标只能修改调用 UID
所拥有的自身包；管理端可以直接管理任意合法包。备份和恢复要求编辑关闭，并在维护租约
期间阻止所有 mutation。该决定只约束授权与并发，不改变规则匹配或动作含义。
