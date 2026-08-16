# ADR-0001：RuleRecord 的内部组件边界

- 状态：接受
- 适用版本：6.9 稳定线后续内部收敛
- 外部兼容目标：v6.9.0

## 背景

`RuleRecord` 原先同时承载目标匹配、规则效果、展示元数据、图片预览和采集时的原始
View 状态。不同模块通过直接读写这些字段判断规则身份、比较 Runtime 差异、保存备份
和恢复 View，导致字段职责和可变性混在一起。

简单地在每个调用点复制一份“小规则表”只能减少读取字段，不能消除多个副本之间的
权威关系，也会让转换代码成为新的行为分叉点。

## 决策

`RuleRecord` 保留为外部 wire 兼容聚合对象，但在内部只组合不可变组件：

1. `MatchSpec` 独占稳定匹配字段。
2. `RuleEffect` 独占稳定效果字段，并由 `RemoveEffect`、`ModifyEffect` 表达两种效果。
3. `RuleSlotKey` 由权威包范围和 `MatchSpec` 派生，不存储、不传输。
4. 版本、别名、预览图、采集原始值等未完成稳定建模的字段暂留在 `RuleRecord`，但不参与
   Runtime 身份或效果比较。
5. `RuleDraft` 只属于 Editor 的构造辅助，不保存 Activity owner、异步请求、位图或保存
   generation。

RuleRecord 的 JSON 和 Parcelable 边界使用专用兼容编解码：内部组件在读取时构造一次，
写出时展开为原有扁平键和原有 Parcel 槽位顺序。不得输出 `matchSpec`、`effect` 等嵌套
规则字段，也不得在 RuleRecord 中保留稳定字段的重复影子。

## 语义边界

- `MatchSpec.equals` 表示原始持久化值相等；Runtime 比较另行表达 repeatable 规则忽略
  文本/描述等有效匹配语义。
- `RuleSlotKey` 只回答“哪个可替换目标槽位”，不回答“当前应用了什么效果”。
- `RuleEffect` 只回答“规则要求写入什么”，不携带运行时 View 基线。
- `AppliedState` 只记录本次 View 接管的基线和属性所有权，撤销时使用 compare-before-restore。
- 展示字段变化不得单独触发规则效果撤销和重应用。

## 兼容与回滚

本决策不修改公开 AIDL、Parcelable 字段顺序、ZIP V1 或规则 JSON 键名。若组件迁移中发现
无法维持旧格式的边界，应停止删除旧字段，先保留兼容编解码和测试，而不能通过增加新的
wire 字段或嵌套 JSON 绕过问题。

迁移期间保留旧版本标签和历史提交，不改写 Git 历史；未实际运行的设备验收继续标为
`NOT_RUN` 或 `BLOCKED`。
