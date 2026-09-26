# GitHub 发布安装包

仓库通过 GitHub Actions 在推送 `v*` 标签时自动构建 Release APK，并创建对应的 GitHub Release。APK 使用项目发布签名密钥签名，便于用户后续覆盖升级。

## 一次性配置签名密钥

发布密钥必须长期保管并备份。后续版本必须继续使用同一密钥签名；丢失或更换密钥后，用户将无法直接覆盖安装升级。

在可信任的本机生成密钥库：

```bash
keytool -genkeypair -v \
  -keystore bilisb-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias bilisb
```

不要将密钥库或密码提交到仓库。项目的 `.gitignore` 已忽略常见密钥文件和本地签名配置。

在 GitHub 仓库打开 **Settings → Secrets and variables → Actions**，新增以下 Repository secrets：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 密钥库文件的 Base64 内容（Linux 可用 `base64 -w 0 bilisb-release.jks` 生成） |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名，例如 `bilisb` |
| `KEY_PASSWORD` | 密钥密码 |

密钥库 Base64 内容只会作为 GitHub Actions Secret 使用；不要将输出粘贴到代码、提交记录或公开日志中。

## 发布新版本

1. 在 `app/build.gradle.kts` 更新 `MODULE_VERSION_NAME` 和 `MODULE_VERSION_CODE`。
2. 在 `app/src/main/resources/META-INF/xposed/module.prop` 同步更新 `versionName` 和 `versionCode`。构建会检查两处是否一致。
3. 提交并推送版本变更到 GitHub。
4. 为该提交创建并推送与版本名一致的标签。例如版本 `1.0.0` 对应标签 `v1.0.0`：

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

5. 在仓库 **Actions** 页面查看“发布安装包”工作流。成功后，在 **Releases** 页面可找到该版本和 `哔哩哔哩空降助手-v1.0.0.apk` 附件。

工作流会检查标签版本和 `MODULE_VERSION_NAME` 是否相同；缺少签名 Secrets 或版本不匹配时会停止，不会发布未签名或版本标错的 APK。Release 说明由 GitHub 根据标签间的提交自动生成，发布前可在 GitHub Releases 页面检查或编辑。
