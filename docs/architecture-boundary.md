# 项目架构边界准则

> **版本：** post-6.11.0 主线（6.11.0 之后 20+ 重构提交沉淀）。
>
> **地位：** 本文件是上述重构的**评审执行标准**，
> 与 [`6.10 IPC 与权威边界`](6.10-ipc-authority.md)、
> [`ADR-0002`](adr/0002-6-10-ipc-authority.md)、
> [`ADR-0001`](adr/0001-rule-record-wire-components.md)
> 同属 `docs/` 权威层。本文件只规定**依赖方向、状态所有权与职责归属**，
> 不定义 wire 格式、IPC 语义与匹配含义（那三者归上述三份合同）。
> 与合同冲突时以合同为准。
>
> **判定通则：** 下表 grep 口径均指**代码引用**（import、类型、调用）；
> javadoc 考古自述句（`抽取/搬迁/平移自…`）与版本注记不计入，
> 评审时先排除 `^\s*\*` 注释行再判定。

## 一、分层模型（L0–L6）

```text
L0 Injection      进入进程、装 Hook、转译事件/端口、装配依赖、资源注入
L1 Target Runtime 生命周期编排、规则快照消费、运行时投影、作用域隔离
L2 Engine         match / diff / apply / revoke（纯规则语义，零 Android 设施依赖）
L3 Editor         会话、交互、UI 流程、变更草稿（经端口提能力，不直调设施）
L4 IPC Client     职责门面群（连接/读/观察/租约/写入/图片/日志），真单例共享同一连接核
L5 system_server  唯一写入权威（权限/租约/持久化/撤销/观察者/资产）
L6 Wire           扁平 JSON / Parcelable / ZIP V1（冻结，只读）
```

## 二、依赖方向铁律

### 2.1 允许（且仅允许）

```text
inject      → Port / Event / orchestrator（装配与转译所需）
orchestrator→ Port（能力接口）/ rule / engine / ipc 门面（读/观察）
editor      → Port / rule / ipc 门面（读/写）/ engine
ipc 门面    → ServiceConnection（唯一连接核）/ contract / rule
```

### 2.2 禁止（提交前必须 grep 自证）

| 禁区 | 判定命令（零命中为准） |
|---|---|
| `orchestrator/` 引 Xposed 或注入实现 | `grep -rn "de\.robv\|inject\.hooks\|inject\.HookRegistry" app/src/main/.../orchestrator` |
| `editor/` 引 Xposed | `grep -rn "de\.robv\|XposedHelpers\|XC_MethodHook" app/src/main/.../editor` |
| Runtime 写规则快照 | `RuleLifecycleManager` 内 `replaceRules` 零命中（读 `viewRules` 仅允许投影读） |
| 事件双源 | 同一语义事件全仓唯一 `post` 点（`new RulesChangedEvent` 仅 `RuleManager` 一处；`RESUME` 仅 `onPostResume` 一处） |
| 已删类复活 | `import …RuleServiceClient` / `new RuleServiceClient` / 类型引用全仓零命中；`ActivityResumeHook` / `getViewId` 全仓零符号引用（javadoc 考古句除外） |
| 运行时比较误用展示相等 | `ipc/`、`orchestrator/` 内禁裸 `.contentEquals(`（`RuntimeRuleComparator.contentEquals` 与 UI 层 DiffUtil 用途除外） |

### 2.3 端口优先

新增跨层能力时：**先定义能力接口（Port），再决定实现归属**。
`inject → editor/orchestrator` 的实现边只允许出现在端口实现与
`AppInjector` 装配点；普通业务类之间禁止跨层直接引用实现类。
（先例：`RepeatableRuleGate`、`RecyclerBindingPort`、
`ImagePickPort`、`EditorInteractionPort`。）

## 三、状态所有权

| 状态 | 唯一 Owner | 他人只许 |
|---|---|---|
| 规则快照 | `RuleManager`（发布后替换） | 读事件自带 old/new；禁回读活引用做 diff |
| 编辑权威 | system_server（lease/editRevision） | 客户端持投影（`ClientEditState` 随观察者） |
| 视图基线 | 各 `Applier`（compare-before-restore） | 跨 Activity 禁共享（禁 `getDefault` 回退，空 activity 兼容分支除外） |
| 连接/epoch | `ServiceConnection` 真单例 | 各门面构造直连，禁自建第二核 |
| 日志写入器 | `ServiceBootstrapper`（系统）/`LogBridge`（应用） | 新增安装点必须同步 `verify-stabilization.ps1` 白名单 |
| Hook 门控 | `HookRegistry`（是否干扰宿主） | Runtime 只表达意愿（经 Gate），不碰实现 |

## 四、事件语义

1. **一事实一事件**：一个 Android 回调只对应一个内部事件发布点
   （`RESUME` 唯一源 `onPostResume`）。
2. **事件带显式状态**：跨层事件必须自带消费所需的 old/new 快照，
   禁止依赖“同步派发顺序下去读发布端活引用”。
3. **Hook 只转译**：Hook 体 = 参数提取 + `post`/端口回调 + 异常吞入日志；
   开关判断、时机策略、导航、业务分支一律归订阅侧/端口实现。
4. **注册顺序即语义**：依赖分发顺序的订阅（如 DESTROY 先 Editor 后 Runtime）
   必须在 `AppInjector` 集中注释写明，不得隐含。
5. **配置事实单一来源**：`CONFIG_CHANGED` 是语义配置事件的唯一来源。
   `DecorView` layout observer 仅用于观察事实并触发统一 reconcile 安全网，
   不发布 EventBus 事件、不拥有重建策略，也不自动打开编辑器。
6. **配置快照是最小投影**：`ActivityConfigurationSnapshot` 只表达编辑器资源选择
   和面板测量所需字段，不是完整 Android `Configuration` 替代品；locale、keyboard、
   navigation、window 等未投影字段继续由 App 层基础 `Configuration` 保留。

## 五、Xposed 层职责（9 进 10 禁）

**允许**：进三进程；找类装 Hook（分装独立、可重试）；提参；
ClassLoader 隔离；转事件/端口；注模块资源；异常隔离；
system_server 服务注册；`AppInjector` 集中装配。

**禁止**：规则匹配/diff/应用；ViewController；编辑器状态机；
Undo/mutation 对账/Lease/SharedMemory/Observer/repository；
业务 UI；UI 展示知识（Toast 文案归 Panel，经端口回调）；
`engine/runtime/rule/ipc/editor` 反向依赖 Xposed。

## 六、IPC 层职责

1. `ServiceConnection` 只回答“能不能连”（建连/epoch/死亡/诊断），
   不理解 mutation 语义；回调不得在连接锁内做 Binder IPC（观察项）。
2. 写入、读取、观察、租约、图片、日志各有唯一门面，**共享同一
   `ServiceConnection` 真单例，禁自建第二连接核**（epoch/连接态分裂即故障）。
3. 新增 IPC 能力先归位到现有门面，确无归属才建新门面。
3. `getDefault()` = 生产 canonical 实例，构造器公有仅作 DI/测试缝，
   禁止生产代码 `new` 第二份状态门面。

## 七、Xposed 入口静态链 fork 安全红线（常驻门禁）

LSPosed 在 Zygote fork 期初始化入口类，此时主 Looper 尚未 prepare。
`ModuleBootstrap` 静态链（→`EditorOrchestrator`→`RuleEditorClient`→各门面）
的**构造路径禁止触碰 Looper/Handler/Binder/Context**（已锁：
`inject/XposedEntryForkSafetyTest` + `verify-stabilization.ps1` 点名）。
新增入口期构造内容必须通过该单测。

## 八、修改纪律

1. 先职责后结构：复用/新增/拆分皆为工具，以“职责归属”而非“改动大小”定方案。
2. 小步可验证：行为变更与结构变更不同 commit；门面/端口先立后切，
   旧入口删除独立成 commit（以“删后编不过”为收口标准）。
3. 命名跟随职责：类搬家、TAG、日志前缀随 owner 走，提交说明注记日志键变化。
4. 以下事项**触发式处理、不主动立项**：`ModuleBootstrap` 入口状态桥、
   `Orchestrator` 内部分拆、`Factory` 两阶段、`ModuleResources` 门面化、
   连接锁临界区优化、单播改多播。

## 九、验证门禁（与修改同提交）

`assembleDebug`（300s）+ `verify-stabilization.ps1` +
全量 JVM 单测 + engine/app 实测 + 持久化日志门禁；
触及注入/生命周期/IPC 者须在重启后新注入链上复验。
