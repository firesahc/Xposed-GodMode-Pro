# ADR-0002：6.10 IPC 与写入权威

- 状态：已接受
- 适用版本：`6.10.0`（协议版本 `61000`）
- 取代：6.9 历史线中的旧 `IGodModeManager`/`IObserver` IPC

## 决策

6.10 采用单一的新 IPC 合同，不保留旧 descriptor、旧 AIDL 或空规则 fallback。
客户端首先读取 Binder descriptor，再通过 transaction 1 获取包含协议版本、构建版本、
合同指纹和服务状态的 `ServiceIdentityParcel`；不匹配时进入粘性的 `REBOOT_REQUIRED`，
管理端停止读写，运行时停止新的业务 Hook。瞬时 Binder 死亡只影响当前连接身份，
客户端清除本地租约与事件水位；重连必须重新握手和注册 Observer，但不得自动恢复编辑。

system_server 是规则、图片、配置和日志的唯一写入权威。所有 mutation 通过
`ILeaseOwner` 绑定调用 UID、请求包和一次性 token。管理端可在非维护期直接
修改任意合法包；目标 UID 只有在全局编辑开启时才能修改其 UID 拥有的请求包。全局编辑
不绑定单一目标，多个目标可以各自持有短 mutation 会话并发准备，但 Repository 仍按提交
线性化持久化。shared UID 只证明 UID 拥有请求包，不提供进程级包身份保证。

工具栏配置读取按包作用域授权：管理端使用 `GLOBAL_SCOPE`，目标进程只能读取其 UID
拥有的包；配置写入仍是管理端专属 mutation。

关闭编辑进入 `CLOSING` 并拒绝新 mutation，等待已受理提交完成；10 秒内未完成则返回
`BUSY` 并保持关闭中。客户端不得复用旧 token 重新开启，必须等待关闭 revision 到达。
owner death 清理租约，但不能中断已进入持久化的提交。
备份、恢复与编辑/普通 mutation 互斥。恢复允许逐规则部分成功，但每条规则和它的图片资产
必须作为一个原子单元提交。

备份使用独占 `OP_BACKUP` 读租约。客户端在租约内重新读取权威规则快照并导出图片，
备份期间所有 mutation 返回 `BUSY`，租约在完成或 owner death 后释放。

读取通过 `RuleSnapshotParcel` 传递现有扁平 JSON 的只读 `SharedMemory`，并携带
状态、包名、generation、长度和 SHA-256。Observer 只发送包名与 committed generation，
编辑状态另携带单调 edit revision；客户端按连接 epoch、revision 和 generation 拒绝旧事件，
并拒绝长度或摘要不符的快照。

图片写入使用单次 FD mutate，不保留远程资产会话。客户端为主图和修改图创建匿名 pipe，
同时启动两个写端；一次 `mutate()` 携带规则 JSON 与两个可空输入 FD。服务端在完成身份、
包名和租约校验后，把每个 pipe 有界复制到私有 `.incoming-*` 常规文件，再用现有安全
解码器验证。规则文件和正式图片仍由 Repository 先持久化再发布；只有磁盘提交成功后才
更新内存快照、generation 和 Observer。

合同指纹固定为 `iruleservice-61000-fd-mutate-v2`。旧会话合同与新合同不互操作，更新
APK 后必须重启 system_server。Binder 回复丢失时客户端进入 `UNCERTAIN`，不重放请求；
重连后仅通过权威快照按 `RuleSlotKey` 和内容进行只读对账。

## 保持不变的边界

本 ADR 只改变跨进程所有权、传输和生命周期协议，不改变 RuleRecord JSON、
Parcelable、ZIP V1、图片路径、matcher 或 effect 语义。不做旧规则数据迁移，
也不引入 RuleDocument、TargetPlan、ActionPlan、RuleId 或 SemanticRevision。

## 失败与验收

非 READY 状态不能伪装为空规则。写入失败必须保持旧快照和 generation；恢复返回
逐条结果，不能按输入数量伪报成功。自动化门禁通过只是静态/JVM/构建证据，发布
仍需要同一候选 SHA 上的真实 Binder、LSPosed、SELinux/owner、不同 ROM Hook、
Recycler 快速复用、detach/destroy 和异步图片验证。
