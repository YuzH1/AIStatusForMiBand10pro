# AI Status for Mi Band 10 Pro

在小米手环 10 Pro 上实时查看 AI 账户额度与用量，支持官方与中转多平台，低额度自动通知提醒。

![Release](https://img.shields.io/github/v/release/YuzH1/AIStatusForMiBand10pro)
![License](https://img.shields.io/github/license/YuzH1/AIStatusForMiBand10pro)
![Platform](https://img.shields.io/badge/platform-Mi%20Band%2010%20Pro%20%2F%20Android-black)
![Vela](https://img.shields.io/badge/Vela%20QuickApp-JS-blue)
![Android](https://img.shields.io/badge/Android-Kotlin-green)

> ⚠️ 本项目为个人自用开发，涉及非官方协议（interconnect 通信与 rpk 安装流程），仅供学习与个人使用。

## 目录

- [功能特性](#功能特性)
- [架构](#架构)
- [支持的数据源](#支持的数据源)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [后台保活与断连排查](#后台保活与断连排查)
- [常见问题 FAQ](#常见问题-faq)
- [签名说明](#签名说明)
- [贡献](#贡献)
- [许可证](#许可证)
- [致谢](#致谢)

## 功能特性

- **手环端**：多账户额度卡片（剩余额度、进度条、低额度变色）、5 分钟自动刷新、缓存优先加载（打开即显示，不闪红）、连接诊断、返回桌面
- **手机端**：
  - 定时轮询全部账户（默认 10 分钟，可调）
  - 额度变化时自动推送通知到手环（无需打开快应用）
  - 低额度阈值提醒（按剩余/总额百分比）
  - 测试通知按钮、运行日志回显，便于排查
- **多数据源混用**：同时配置任意数量的 OpenAI / DeepSeek / Codex / 中转站 / Sub2API / 手动账户

## 架构

```
┌──────────────────────────────────┐
│ 手环10 Pro · Vela 快应用 (quickapp/) │  额度卡片 + 缓存 + 刷新/诊断/返回
└──────────────┬───────────────────┘
               │ @system.interconnect（BLE，包名+签名一致校验）
┌──────────────┴───────────────────┐
│ Android 伴侣App (companion/)       │  前台桥接服务 + 定时轮询 + 通知推送
└──────────────┬───────────────────┘
               │ HTTPS
┌──────────────┴───────────────────┐
│ OpenAI / DeepSeek / Codex / 中转站 │
│ Sub2API / one-api 额度接口         │
└──────────────────────────────────┘
```

## 支持的数据源

| 类型 | 接口 | 说明 |
|---|---|---|
| `oneapi` | `GET {base}/api/status` 或 `/api/user/self` | one-api / new-api 中转站，自动兼容 `/v1` 后缀 |
| `openai` | `/v1/dashboard/billing/subscription` + `/usage` | 订阅制：限额-已用；无订阅自动走 `credit_grants`（prepaid 总额度） |
| `deepseek` | `GET {base}/user/balance` | 官方余额 |
| `codex` | `GET https://chatgpt.com/backend-api/wham/usage` | Codex/ChatGPT 订阅，5 小时+7 天窗口已用百分比、充值余额；access_token 取自 `~/.codex/auth.json` |
| `sub2api` | `GET {base}/v1/usage` | Sub2API 中转网关，兼容订阅/总额度/速率限制/余额四种模式 |
| `manual` | 无 | 手动填写剩余/总额/单位（ChatGPT Plus 等无查询接口的账户） |

## 目录结构

```
ai-quota-watch/
├── quickapp/              # Vela 快应用（手环端）
│   ├── src/manifest.json  # package 与 Android 包名一致（com.ivy.aiquota）
│   ├── sign/              # 签名 pem（与 Android jks 同源）
│   └── src/
│       ├── pages/index/index.ux   # 主界面（额度卡片 + 固定底部操作栏）
│       └── utils/         # interconnect 封装 + JSON-RPC 客户端
├── companion/             # Android 伴侣App（AGP 8.5 / Kotlin）
│   ├── app/libs/          # xms-wearable-lib_1.4_release.aar（小米官方 SDK）
│   ├── signing/           # keystore + 签名配置（内置 Demo 签名可开箱即用）
│   └── app/src/main/java/com/ivy/aiquota/
│       ├── WearBridgeService.kt   # 前台桥接服务（节点握手/消息路由/通知推送）
│       ├── BridgeRouter.kt        # JSON-RPC 路由
│       ├── quota/         # 6 种数据源 Provider + 轮询管理
│       └── ui/MainActivity.kt     # 配置界面 + 通知设置 + 运行日志
├── .github/workflows/     # 打 tag 自动构建 rpk + APK 并发布 Release
└── scripts/               # 签名生成 / 图标生成脚本
```

## 快速开始

### 环境要求

| 项目 | 要求 |
|---|---|
| 手机 | Android，已安装**小米运动健康**并与手环配对 |
| 手环 | 小米手环 10 Pro（Vela 快应用） |
| 构建 | Android Studio（AGP 8.5 / Gradle 8.7 / JDK 17）；Node.js 18.x + AIoT-IDE 或 npm |

### 第 1 步：构建并安装 Android 伴侣App

1. Android Studio 打开 `companion/`，等 Gradle Sync 完成
2. 手机开 USB 调试并连接 → Run（debug 构建自动使用 `signing/` 里的签名）
3. 打开 App → 添加账户（类型/BaseUrl/Key/提醒阈值）→ 启动桥接服务
4. 通知栏出现「AI额度桥接」即成功；可点「发送测试通知到手环」验证

> 也可直接下载 [Releases](https://github.com/YuzH1/AIStatusForMiBand10pro/releases) 中的 APK 安装。

### 第 2 步：构建并安装手环快应用

**打包**（二选一）：

- **AIoT-IDE**（官方，推荐）：打开 `quickapp/` → 安装依赖 → 点「打包」，产物在 `quickapp/dist/*.debug.rpk`
- **命令行**：
  ```bash
  cd quickapp
  npm install
  npm run build      # → dist/*.debug.rpk
  ```

**安装到手环**（官方 App 的 Debug 入口仅对合作开发者开放，需使用社区工具）：

- **minstall**（推荐，开源）：<https://github.com/HyperionD/minstall>
  1. 提取 authkey：小米运动健康 → `我的 → 关于` → 连续点击 App 图标 → 导出日志，取 `Download/wearablelog/` 最新 zip 中的 `"encryptKey"`
  2. 手环 `设置 → 我的设备 → 连接新手机`，minstall 连接后选 rpk 安装
- **表盘自定义工具**（米坛社区 BandBBS）：设备切换至「小米手环10 Pro」→ AuthKey 读取 → 蓝牙一键安装

### 第 3 步：验证

手环打开「AI额度」→ 顶部显示「已连接」→ 卡片显示各账户额度。手机端保持桥接服务运行即可；手环上「诊断」可查看连接状态码（`0`=正常，`1001`=包名/签名不匹配）。

## 配置说明

| 配置项 | 位置 | 说明 |
|---|---|---|
| 账户（类型/BaseUrl/Key/阈值） | 手机 App → 添加账户 | BaseUrl 填站点根路径，不要带 `/v1` |
| 轮询间隔 | 手机 App → 轮询间隔(分钟) | 默认 10 分钟 |
| 通知开关 | 手机 App → 通知设置 | 额度变化推送 / 低额度提醒 / 测试通知 |
| 低额度阈值 | 每个账户单独设置 | 按 剩余/总额 百分比，余额制账户不触发 |
| 运行日志 | 手机 App → 运行日志 | 实时回显服务日志，排查利器 |

## 后台保活与断连排查

长时间断连通常是**系统杀后台**导致。给「AI额度」和「小米运动健康」都设置：

1. 应用信息 → 电池/耗电 → 「无限制」
2. 最近任务里下拉**锁定**
3. 设置 → 应用设置 → 授权管理 → 自启动打开
4. 关闭系统智能省电/夜间清理

App 的「运行日志」可区分断连环节：`service started` 重复出现 = App 被杀重启；`小米穿戴服务断开` = 运动健康被杀；`手环节点断开` = 蓝牙连接掉线（服务会自动重连，且每 60 秒周期重挂消息监听）。

## 常见问题 FAQ

- **手环显示「未连接」/「刷新失败」**：检查桥接服务是否运行、小米运动健康是否连着手环、两端包名与签名是否一致（换签名后必须重装两端）
- **中转站 404**：BaseUrl 填站点根路径（如 `https://xxx.com`，不要带 `/v1`）；纯 Codex/Responses 中转若没有余额接口，改用「手动」类型
- **Codex 需要代理**：`chatgpt.com` 被墙，手机需挂代理才能查询；中转站和 DeepSeek 可直连
- **Codex 401**：access_token 过期，重新 `codex login` 后更新 App 里的 Key
- **重装 rpk 前先卸载旧包**：表盘自定义工具 → 工具 → 快应用管理

## 签名说明

interconnect 通信要求**两端包名一致 + 签名一致**。项目内置小米官方 Demo 的签名（keystore 密码 `xmswearable`）可开箱即用；正式自用建议生成自己的：

```powershell
# 1. 生成自己的 keystore
keytool -genkeypair -v -keystore mykeystore.jks -alias aiquota -keyalg RSA -keysize 2048 -validity 36500 -storepass <密码> -keypass <密码> -dname "CN=ai-quota"

# 2. 同步签名到两端（自动更新 companion/signing + quickapp/sign）
.\scripts\gen-signature.ps1 -JksPath "mykeystore.jks" -StorePass <密码> -KeyAlias aiquota -KeyPass <密码>
```

详见 [`companion/signing/README.md`](companion/signing/README.md)。

## 贡献

欢迎提交 Issue 与 PR：

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feature/xxx`
3. 提交改动：`git commit -m "feat: xxx"`
4. 推送分支：`git push origin feature/xxx`
5. 提交 Pull Request

注意：修改 JSON-RPC 协议时，必须同步更新 `quickapp/src/utils/api.js` 与 `companion/.../BridgeRouter.kt` 两端。

## 许可证

[MIT](LICENSE) © 2026 YangZifan (YuzH1)

## 致谢

- [小米 Vela JS 应用文档](https://iot.mi.com/vela/quickapp/zh/guide/) - 快应用开发
- [interconnect 设备通信 API](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html)
- [小米穿戴第三方 APP 能力开放接口文档](https://vela-docs.cnbj1.mi-fds.com/vela-docs/files/%E5%B0%8F%E7%B1%B3%E7%A9%BF%E6%88%B4%E7%AC%AC%E4%B8%89%E6%96%B9APP%E8%83%BD%E5%8A%9B%E5%BC%80%E6%94%BE%E6%8E%A5%E5%8F%A3%E6%96%87%E6%A1%A3_1.4.pdf)
- [minstall - 手环 rpk 直装工具](https://github.com/HyperionD/minstall)
- [Sub2API](https://github.com/Wei-Shaw/sub2api)
- [tgwear-quickapp - JSON-RPC 架构参考](https://github.com/hrk666666/tgwear-quickapp)