# 全项目设备测试规则

本文件是全项目设备测试的统一执行规则，适用于当前版本、历史稳定分支、
独立 `test-target` 以及所有 app/engine instrumentation。它补充自动化测试
命令，不改变 RuleRecord、IPC 或运行时合同。

## 前台规则

任何依赖前台 Activity、View、RecyclerView、图片回调或注入宿主状态的软件测试，
必须在每个测试方法开始前由测试脚本显式执行：

```powershell
adb shell su -c "am start -W -n <package>/<test-activity>"
```

脚本还必须确认该 Activity 进入 `RESUMED`；仅看到 `Status: ok` 不足以证明
宿主已经在前台。宿主未进入 `RESUMED` 时，测试应标记为基础设施失败，不能
把 `Test host Activity was not started` 归因于规则、Recycler 或图片逻辑。

不依赖前台 Activity 的 Parcelable、FD、图片输入和 Binder 合同测试可以直接由
`am instrument` 运行，但仍必须先执行日志目录检查，并与前台宿主测试串行执行。

## 日志规则

每轮设备测试开始前，先检查并记录持久化日志根目录；所有测试都从该目录
核对日志，不从 LSPosed 管理器或临时 logcat 摘取。固定路径是：

```text
/data/misc/godmode/
```

本项目当前源码常量和设备实际目录均为 `/data/misc/godmode/`，主日志为
`/data/misc/godmode/godmodepro.log`。测试报告必须写明该路径。

主日志每条记录使用以下单行格式：

```text
MM-dd HH:mm:ss.SSS pid LEVEL/tag: [package] message
```

其中 `pid` 和时间戳来自产生日志的进程/调用方，`LEVEL` 为 `V/D/I/W/E/A`；
若 IPC 收到未定义的 Android 日志级别，写入器使用 `?` 作为非法级别标记，便于
设备审计发现调用方合同错误。
消息中的反斜杠、回车和换行分别转义为 `\\`、`\r` 和 `\n`，因此一条日志不得
跨越多个物理行。system_server 是持久化日志唯一写入方；应用端转发属于尽力而为，
Binder 尚未就绪时不应为了写日志反向触发连接重试，客户端会保留有界待发送队列，
重连后按原时间戳补发，队列溢出则保留最新记录并在恢复后报告丢弃数量。

## 执行顺序

1. 保持设备亮屏并解除锁屏。
2. 检查日志目录并记录本轮开始时间。
3. 安装测试 APK（只允许覆盖安装，不卸载、不清空规则数据）。
4. 对每个前台测试方法先执行 `am start -W`，再执行 `am instrument`。
5. 读取实际日志目录，按 requestId、包名和时间段核对结果。
6. 将 JVM、构建、instrumentation、Binder、LSPosed、SELinux 和 ROM 证据分开记录。

需要把持久化日志作为本轮 instrumentation 的硬门禁时，执行脚本时追加
`-RequirePersistentLog`；它会检查主日志非空、0600 权限和最近 200 行的单行格式。

## 测试记录要求

每轮记录至少包含：设备型号/API、测试 APK 版本、开始时间、是否覆盖安装、
前台 Activity 的 `am start -W` 和 `RESUMED` 结果、日志根目录，以及按包名和
时间段核对出的日志结论。若日志文件因 SELinux 或文件权限无法读取，应明确
记录为“日志读取受限”，不能把它伪报为日志通过或产品失败。
