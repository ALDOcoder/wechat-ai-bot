# Vendored Source Notice

The files under `src/main/java/io/uouo/wechat/` are vendored from:

- 原项目（已归档）：https://github.com/biezhi/wechat-api （作者 biezhi）
- UOS fork：https://github.com/UoUoio/WeChat-API-UoUo （作者：青衫 / UoUoio）
- 开源协议：Mozilla Public License 2.0（MPL-2.0）

本项目在 fork 源码基础上做了以下修改（均围绕让 2026 年仍可登录网页版微信）：

1. `WeChatApiImpl#processLoginSession`：适配 UOS 协议——新版登录响应不再返回 XML 凭证，
   改为从响应 Cookie 获取 `wxsid`/`wxuin`，`skey` 置空、`pass_ticket` 使用 `deviceid`；
2. `WeChatApiImpl#loadContact`：修复原版笔误（`null == getUserName()` → `null != ...`），
   使联系人列表能真正加载，好友类型过滤(accountType)恢复生效；
3. `WeChatApiImpl#getUUID`：修正被截断的 `redirect_uri`/`lang` 参数；
4. `BotClient`：移除强制 `https.protocols=TLSv1` 的系统属性（JDK 17+ 下会破坏
   同 JVM 内其他 HTTPS 调用，例如 Spring AI 调用大模型）；
5. 未修复的部分：`auto-login=true` 且无登录缓存时的重复登录 bug（见 application.yml 注释，
   请保持 `wechat.bot.auto-login: false`）。

Any subsequent use of this vendored code must keep this notice and comply with MPL-2.0.
