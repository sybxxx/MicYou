# Windows 环境下 Rustls 与 Reqwest 编译避坑指南

## 1. 核心教训与报错现象
在 Windows 开发环境中编译 Tauri / Rust 桌面端时，如果使用了 `rustls 0.23+`、`tokio-rustls` 或 `reqwest`，默认配置可能会引入 `aws-lc-rs` / `aws-lc-sys`。
在缺少 NASM（Netwide Assembler）编译器的 Windows 机器上，会导致以下典型构建崩溃：
```
error: failed to run custom build command for `aws-lc-sys v0.41.0`
thread 'main' panicked at ... nasm_builder.rs: NASM failed for ... chacha-x86_64.asm
```

## 2. 避坑指南与解决方案
1. **统一禁用 `aws-lc-rs` 默认提供商，切换至纯 C/ASM 的 `ring`**：
   在 `src-tauri/Cargo.toml` 中明确声明 `default-features = false` 并显式启用 `ring`，切勿在 features 中加入 `"default"`：
   ```toml
   # 正确示例：
   rcgen = { version = "0.13", default-features = false, features = ["ring", "pem"], optional = true }
   rustls = { version = "0.23", default-features = false, features = ["ring", "std", "tls12", "logging"], optional = true }
   tokio-rustls = { version = "0.26", default-features = false, features = ["ring", "logging", "tls12"], optional = true }
   ```
2. **`reqwest` 使用 Windows 原生 TLS 或 Ring**：
   ```toml
   # 使用 Windows Schannel 原生安全栈：
   reqwest = { version = "0.13.4", default-features = false, features = ["json", "native-tls"] }
   ```
3. **特征污染排查命令**：
   若后续再次遇到 `aws-lc-sys` 被引入，使用以下命令精准定位依赖源：
   ```bash
   cargo tree -i aws-lc-sys
   ```
