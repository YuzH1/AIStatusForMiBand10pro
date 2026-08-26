# 签名说明（重要）

手环快应用与手机 App 之间的 interconnect 通信会校验 **包名 + 签名**，两者必须一致。

本项目已内置一份可用的签名（来自小米官方 Demo 的 keystore，密码 `xmswearable`），
开箱即可跑通。**正式自用建议生成自己的签名**：

1. 生成自己的 keystore（Android Studio: Build > Generate Signed Bundle/APK，
   或命令行）：

   ```bat
   keytool -genkeypair -v -keystore mykeystore.jks -alias aiquota ^
     -keyalg RSA -keysize 2048 -validity 36500 -storepass <你的密码> ^
     -keypass <你的密码> -dname "CN=ai-quota"
   ```

2. 运行签名同步脚本（Windows PowerShell）：

   ```powershell
   .\scripts\gen-signature.ps1 -JksPath "D:\path\to\mykeystore.jks" `
     -StorePass "你的密码" -KeyAlias "aiquota" -KeyPass "你的密码"
   ```

   脚本会自动：
   - 更新 `companion/signing/keystore.properties`
   - 用 `keytool` 把 jks 转成 p12
   - 用 `openssl` 把 p12 转成 pem 并拆出 `private.pem` / `certificate.pem`
   - 同步到 `quickapp/sign/debug` 和 `quickapp/sign/release`

3. 重新构建并安装 **两端**（APK + rpk），签名不一致时手环端会报连接失败。

注意：`.pem` 私钥请勿提交到公开仓库。