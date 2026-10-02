# Android 签名与构建

本仓库不包含发布签名密钥或密码。普通本地调试使用 Android 的 debug 签名即可：

```bash
cd familychat_app
./gradlew :app:assembleBetaDebug
```

Windows 使用 `gradlew.bat`。APK 位于 `app/build/outputs/apk/beta/debug/`，当前原生库 ABI 为 ARM64/ARMv7；优先使用 ARM 真机或兼容的 ARM 模拟器。

需要独立 release 签名时，通过 Android Studio 的签名向导或 `keytool` 创建自己的密钥，复制 [keystore.properties.example](keystore.properties.example) 为忽略的 `keystore.properties`，填写自己的路径和密码。不要使用其他人的发布密钥。

构建配置读取 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；相对路径基于 `familychat_app/`。服务地址另见 [familychat.properties.example](familychat.properties.example)。

本地 release 构建可使用 `scripts/build-familychat.ps1 -Channel Beta`。根目录正式版脚本应加 `-SkipUpload` 才仅本地构建；上传需显式配置 SSH 参数，详见 [发布流程](../docs/operations/release-and-deploy.zh.md)。

签名文件、密码属性、APK、AAB 和 mapping 都不进入 Git。现有构建脚本会验证签名并产生本地 build manifest；仓库不保留旧部署的发布记录。
