# Family Chat 服务端安全配置

## 必需配置

生产环境必须设置 `NODE_ENV=production` 和长度至少为 32 个字符的随机 `JWT_SECRET`。缺少或使用示例密钥时，服务端会拒绝启动。

服务端默认只监听 `127.0.0.1:3000`，由同机 Nginx 反向代理。仅当 Node 服务确实需要监听其他地址时才设置 `HOST`。

```ini
Environment=NODE_ENV=production
Environment=HOST=127.0.0.1
Environment=TRUST_PROXY_HOPS=1
Environment=JWT_SECRET=替换为独立随机长密钥
```

## 首次创建管理账号

只有全新数据库且 `manage_admins` 为空时，才需要临时加入以下变量：

```ini
Environment=MANAGE_BOOTSTRAP_USERNAME=你的管理用户名
Environment=MANAGE_BOOTSTRAP_PASSWORD=至少12位的独立密码
```

首次成功启动并确认管理账号可以登录后，应从 systemd 配置中删除这两个变量，再执行：

```bash
sudo systemctl daemon-reload
sudo systemctl restart family-chat
```

现有数据库已经有管理账号时，不需要设置这两个变量，也不会修改现有账号或密码。

## 禁止在生产环境启用的配置

`SEED_DEMO_USERS=true` 只供本地自动化测试使用。生产环境启用时服务端会拒绝启动。

## 数据库保护

启动时会启用 SQLite WAL、外键约束、5 秒锁等待和自动检查点，并执行带版本记录的 schema migrations。若日志出现 `existing foreign key violations detected`，应先备份数据库并检查对应记录，不要直接删除数据库。

## Android 更新包

管理端上传 APK 后，服务端会记录 SHA-256。App 下载后会同时验证文件大小、SHA-256、包名、版本号和签名证书；任一项不匹配都不会打开安装界面。

## Blog 静态加密

Blog 启用时必须配置独立的 32 字节 `BLOG_AT_REST_KEY`。不要复用 `JWT_SECRET`、FCM 私钥或聊天密钥。可以在服务器上生成一次：

```bash
openssl rand -base64 32
```

把输出写入仅 root 可读的 `EnvironmentFile`，不要直接提交到项目压缩包或 Git：

```ini
BLOG_ENABLED=true
BLOG_AT_REST_KEY='替换为上一步生成的值'
```

服务启动时会把旧的 Blog 明文正文、评论和媒体自动迁移为 AES-256-GCM 密文。媒体使用分块加密，视频仍支持 HTTP Range 播放，但磁盘上不会保存临时明文。首次启用前必须备份数据库和 `blog_uploads`。

`BLOG_AT_REST_KEY` 丢失后，现有 Blog 内容无法恢复；更换该密钥前必须先实现受控的重新加密流程，不能直接改环境变量。缺少密钥时 Blog 默认禁用；若明确设置 `BLOG_ENABLED=true` 但密钥无效，服务会拒绝启动，防止静默回退为明文。
