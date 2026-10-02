# 管理后台 TOTP 两步验证

管理后台 `https://manage.example.com/` 支持基于 TOTP 的两步验证，可使用 Google Authenticator 等验证器。

## 已实现内容

- 管理后台登录支持两步验证
- 管理员在后台可自行开启 / 关闭 TOTP
- 开启后，登录流程为：
  1. 输入用户名和密码
  2. 再输入 Google Authenticator 的 6 位动态验证码
  3. 验证通过后才登录成功
- 管理员密码连续输错 3 次会锁定 5 分钟；两步验证的临时挑战另有限时和尝试次数。

服务端数据结构由项目迁移流程维护，不需要手动改数据库。部署整套服务端时参见 [构建与部署流程](release-and-deploy.zh.md)。

## 第一次如何启用 TOTP

### 1. 先用现有用户名密码登录管理后台

地址：

- `https://manage.example.com/`

### 2. 在后台找到“Two-step verification”

点击 `Turn on`。

页面会显示 `Account label` 和 `Setup key`。

### 3. 打开 Google Authenticator

在手机里：

1. 打开 Google Authenticator，添加新账号。
2. 选择手动输入设置密钥。

### 4. 手动输入页面上的信息

- 账户名：填页面显示的 `Account label`
- 密钥：填页面显示的 `Setup key`

### 5. 回到网页输入 6 位验证码

在 `Code shown in the app right now` 输入验证码，点击 `Turn on`。后台显示 `Two-step verification is on.` 即表示成功。

## 以后登录流程

启用后，每次登录都是两步：

1. 用户名 + 密码
2. Google Authenticator 6 位验证码

## 如何关闭 TOTP

在后台：

1. 点击 `Turn off`
2. 输入当前管理员密码
3. 输入当前动态验证码
4. 再点击 `Turn off`

## 说明

- 页面提供手动输入的密钥和 `otpauth URI`，没有二维码；设置密钥只应保存在验证器中。
- 关闭两步验证需要当前管理员密码和动态验证码。
