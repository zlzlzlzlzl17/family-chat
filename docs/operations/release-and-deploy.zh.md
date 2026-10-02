# 可选构建与发布脚本

核心本地运行见 [README](../../README.md)，通用 Linux 部署见 [deployment.md](../deployment.md)。这些发布脚本保留用于参考，不会在普通 `npm test` 或 debug 构建时执行。

## Android

版本号由 `familychat_app/app/build.gradle.kts` 管理。beta 和 official 使用同一 applicationId，覆盖安装仍受签名和 versionCode 限制。

```powershell
./scripts/build-android-beta.ps1
./scripts/build-android-release.ps1 -SkipUpload
```

release 脚本需要自己的签名配置和 Android SDK。输出到忽略的 `familychat_app/artifacts/`；没有历史部署 APK 或 manifest 随源码发布。

## 显式远程配置

仅在你决定部署到自己的服务器时设置以下变量，或者传入等价脚本参数：

```powershell
$env:FAMILYCHAT_SSH_REMOTE = 'user@example.com'
$env:FAMILYCHAT_REMOTE_DIR = '/path/to/app'
$env:FAMILYCHAT_SSH_KEY = '/path/to/ssh-key'
```

变量不会由根 `.env` 自动注入 PowerShell。脚本没有生产 SSH 目标或密钥路径默认值；缺少配置会拒绝上传。

`deploy-web.ps1` 执行测试、复制代码、上传、备份、重启 systemd 并检查本机 HTTP 状态。备份位于远端应用目录的 `backups/deploy/`。它需要远端 SSH 用户具有相应 sudo 权限，以及 Node、npm、rsync、tar 和 systemd；它不是通用服务器安装工具。

后端 shell 脚本属于可选维护工具：`deploy-candidate.sh` 要求明确提供源目录和 `TARGET_DIR`；`validate-production-copy.sh` 要求 `SOURCE_DB` 是可丢弃的数据库快照；`rotate-jwt-secret.sh` 要求明确设置 `ENV_FILE`，并会修改 systemd 和环境配置。不要把这些脚本当作只读检查。
