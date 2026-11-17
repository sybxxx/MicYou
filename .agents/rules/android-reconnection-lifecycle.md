# Android 后台断网自愈与 AudioRecord 生命周期守则

## 1. 核心教训与背景
在 Android 10+ / 14 现代权限沙箱中，当 App 处于锁屏或后台运行时：
- **`AudioRecord` 句柄极其脆弱**：如果因网络抖动而在后台 `release()` 底层 `AudioRecord` 并尝试重新创建，极易被系统权限拦截或返回静音/0字节数据。
- **后台自愈必须“动网不动麦”**：网络断连时，必须**保留底层的 `AudioRecord` 实例与 `startRecording()` 状态**，仅清理与重建 Socket/Selector 传输层及 reader/writer 协程。

## 2. 规避指南与技术规范
1. **网络重试循环下沉至 `AudioEngine` (IO 协程)**：
   - 绝不能将重试循环挂在 ViewModel 或 UI 相关的 `auxiliaryScope` 上（切后台/锁屏容易被系统暂停或丢失上下文）。
   - 在 `AudioEngine.sessionJob` 内部运行指数退避自愈循环（1s -> 2s -> 4s -> max 10s）。
2. **彻底排空硬件缓冲 (`drainAudioRecord`)**：
   - 当 Socket 断开、正在重试等待时，`AudioRecord` 仍在后台持续产生 PCM 数据。
   - `drainAudioRecord()` 必须采用 `while(true)` 循环抽空所有堆积数据，直至读取返回 0。
   - 在握手连接成功（`connectTransport`）瞬间，必须立即执行彻底排空并重置 FEC 状态，防止积压的历史静音/旧帧导致说话出现数秒延迟。
3. **主录音循环采用硬件阻塞读取 (`READ_BLOCKING`) 消除积压延迟**：
   - 坚决避免在主录音循环中使用 `READ_NON_BLOCKING` + `delay()` 轮询。在 Android 息屏省电模式下，系统定时器合并会导致协程 `delay` 严重变慢，进而造成硬件缓冲区不断积压、说话出现数秒延迟甚至语音输入截断。
   - 必须使用 `AudioRecord.READ_BLOCKING`，由硬件中断直接唤醒 IO 线程，实现 sub-20ms 超低延迟实时流传输。
4. **事件驱动快速唤醒 (`wakeupReconnect`)**：
   - Wi-Fi 恢复、mDNS 发现新服务端、用户前台点击等外部事件，应通过 `Channel.CONFLATED` 立即唤醒底层的重连等待，避免白白等待退避定时器。
5. **Windows 中文路径与 SDK 配置**：
   - 项目路径包含中文或非 ASCII 字符时，`gradle.properties` 必须包含 `android.overridePathCheck=true`。
   - `local.properties` 必须正确指定 `sdk.dir`。
