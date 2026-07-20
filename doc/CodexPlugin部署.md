# MindTrain Codex Plugin 部署

## 1. 目标

MindTrain 面向单用户私有部署。服务器运行 Training Core、Trainer MCP 和 PostgreSQL；Codex 通过仓库 Marketplace 安装 MindTrain Plugin，并在首次使用时配置自己的服务器地址和单用户 Token。

```text
Codex
  └─ MindTrain Plugin
       ├─ Knowledge Trainer Skill
       └─ Local MCP Bridge
              │ HTTPS + Bearer Token
              ▼
         Trainer MCP
              │ Bearer Token
              ▼
         Training Core ── PostgreSQL
```

## 2. 服务端部署

在 `deploy/core-only` 中创建 `.env`：

```dotenv
POSTGRES_PASSWORD=<random-database-password>
MINDTRAIN_BOOTSTRAP_TOKEN=<random-single-user-token>
```

启动：

```bash
docker compose up -d --build
```

Compose 将 Core 和 Trainer MCP 映射到宿主机 loopback。反向代理只公开 Trainer MCP：

```text
https://mindtrain.example.com/mcp -> http://127.0.0.1:8787/mcp
```

必须启用 HTTPS。不要公开 PostgreSQL；Core 也没有必要直接暴露公网。

Trainer MCP 的 `/mcp` 要求：

```http
Authorization: Bearer <MINDTRAIN_BOOTSTRAP_TOKEN>
```

该部署模型只有一个用户，因此不引入注册、OAuth 或多租户权限体系。Bootstrap Token 同时用作 Core Token 和 MCP Access Token。

## 3. 添加 Marketplace

稳定版本：

```bash
codex plugin marketplace add shigella520/MindTrain --ref main
```

开发版本：

```bash
codex plugin marketplace add shigella520/MindTrain --ref dev
```

确认 Marketplace：

```bash
codex plugin marketplace list
```

## 4. 安装 Plugin

```bash
codex plugin add mindtrain@mindtrain
```

也可以在 `/plugins` 中选择 MindTrain Marketplace 并安装。安装或升级后，应开启一个新任务，使 Plugin 中的 Skill 和 MCP bridge 被重新加载。

## 5. 首次配置

在新任务中输入：

```text
$knowledge-trainer 配置我的 MindTrain
```

Skill 调用 `get_mindtrain_configuration` 检查本地状态。未配置时，用户提供：

- 完整 MCP URL，例如 `https://mindtrain.example.com/mcp`。
- 服务端 `.env` 中的 `MINDTRAIN_BOOTSTRAP_TOKEN`。

`configure_mindtrain_instance` 会先请求远端 `initialize`。只有 URL、Token 和服务身份验证全部通过后才写入：

```text
~/.config/mindtrain/plugin.json
```

在 Windows 上使用 `%APPDATA%/MindTrain/plugin.json`。也可以通过 `MINDTRAIN_PLUGIN_CONFIG` 指定其他位置。

配置文件权限在 POSIX 系统上设置为 `0600`，状态工具不会返回 Token。不要把该文件复制进仓库、日志或问题报告。

若不希望在对话中提供 Token，可以进入已安装 Plugin 的目录后运行：

```bash
python3 scripts/mindtrain_mcp_bridge.py --configure
```

## 6. 更新 Plugin

```bash
codex plugin marketplace upgrade mindtrain
codex plugin add mindtrain@mindtrain
```

更新后开启新任务。已有私有实例配置保存在 Plugin 缓存目录之外，不会因 Plugin 升级被删除。

## 7. 故障排查

| 现象 | 检查 |
| --- | --- |
| Plugin 列表中没有 MindTrain | 检查 Marketplace 是否添加，并确认所选 Git ref 包含 `.agents/plugins/marketplace.json` |
| `configuration_required` | 运行首次配置流程 |
| `MindTrain rejected the access token` | 确认 Token 与服务器 `.env` 一致，随后重新配置 |
| `401 Unauthorized` | Codex 请求未携带正确 Bearer Token |
| 无法连接 | 检查 HTTPS、DNS、反向代理和 `/mcp` 路径 |
| Skill 或工具未更新 | 重新安装 Plugin 并开启新任务 |
