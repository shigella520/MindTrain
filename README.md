<p align="center">
  <img src="assets/brand/mindtrain-icon.png" width="128" alt="MindTrain 图标" />
</p>

<h1 align="center">MindTrain</h1>

<p align="center">
  <strong>你的私人 AI 知识训练场：边问、边练、边复习，把一次对话变成长期记忆。</strong>
</p>

<p align="center">
  <a href="https://mindtrain.jianyutan.com/">在线演示</a> ·
  <a href="#5-分钟开始使用">快速开始</a> ·
  <a href="doc/CodexPlugin部署.md">Codex Plugin</a> ·
  <a href="doc/部署与运维.md">部署与运维</a>
</p>

<p align="center">
  <a href="https://github.com/shigella520/MindTrain/actions/workflows/ci.yml"><img src="https://github.com/shigella520/MindTrain/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="MIT License" /></a>
  <a href="https://openjdk.org/projects/jdk/21/"><img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21" /></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.5" /></a>
</p>

MindTrain 是一个面向 Codex 和其他 AI 客户端的开源知识训练平台。你可以像普通聊天一样随时追问、质疑和索要提示；MindTrain 在后台负责题库、精确判分、学习记录和抗遗忘调度。

- **对话不中断**：遇到不懂的概念直接问，当前题目不会被误判为已作答。
- **数据归自己**：单用户私有部署，题库和学习历史保存在自己的 PostgreSQL 中。
- **真正形成闭环**：复习旧题、按需生成新题、记录错误，并控制每天的新题与到期积压。
- **知识目录初始化**：既可通过 AI 对话规划领域与知识点，也可指定本地资料目录整理知识树；完整预览并确认后才保存。

MindTrain 核心不绑定具体知识领域；每个私有实例可以独立管理自己的领域、知识点和题库。

## 使用预览

### 如何工作

![MindTrain 内部架构](doc/architecture-overview.svg)

- Training Core 是题目、会话、作答、复习状态和配置的权威数据源。
- Trainer MCP 只调用 Core API，不直接访问数据库。
- Skill 负责教学和工具编排，不保存业务数据。
- AI 临时题会先校验并入库再展示；作答前可拒绝并物理删除，作答后转为普通复习题。

设计决策和数据边界见[概要设计](doc/概要设计.md)。

### 训练流程

![MindTrain 训练流程](doc/training-flow.svg)

一次训练并不是简单的“显示答案”：调度器先根据到期状态和新题额度选题；用户可以在提交前无限追问；只有明确提交选项后，Core 才会精确判分并追加学习记录。题库不足时，Core 会返回结构化生成要求，由 Codex 生成、Core 校验入库后再展示。

每轮题量、复习与新题配额、积压暂停规则和 AI 临时题有效期均由数据库配置控制，可在 Web 管理页调整。

### 在 Codex 中训练、追问和复习

![MindTrain Codex 对话式训练示例](doc/preview-codex-training.png)

在线演示：[https://mindtrain.jianyutan.com/](https://mindtrain.jianyutan.com/)（可公开查看示例统计；训练需要连接自己的私有实例）

## 为什么使用 MindTrain

| 能力 | 你得到什么 |
| --- | --- |
| 对话式 AI 教练 | 在同一题中追问概念、索要提示、质疑答案，再继续作答 |
| 确定性判分 | 单选和多选按选项集合精确判分，不让模型“凭感觉”决定分数 |
| 抗遗忘调度 | 默认平衡复习题和新题；积压过高时自动暂停新题 |
| AI 补题 | 题库不足时由 Codex 按指定知识点、题型和难度生成并校验新题 |
| 本地资料库 | 支持 MD、TXT、PDF、DOCX、PPTX；原文和索引只留在本机 |
| 知识目录 | Codex 与 Web 均可查询多个领域、知识点树、关键词、题目覆盖和掌握度 |
| 私有数据 | Core 是唯一权威数据源；Skill 无状态，仓库不承载运行数据 |
| Web + Codex | Web 用于看板、旧题复习和配置；Codex 用于完整的 AI 教练体验 |

当前可用：Training Core、Trainer MCP、MindTrain Web、Codex Plugin 和加权调度。Anki / FSRS Provider 尚在规划中。

## 5 分钟开始使用

### 前置条件

- Git
- Docker 与 Docker Compose
- Codex CLI 或 Codex App（使用 AI 教练时需要）

### 1. 下载并配置

```bash
git clone https://github.com/shigella520/MindTrain.git
cd MindTrain/deploy/core-only
cp .env.example .env
```

编辑 `.env`，至少替换下面两项：

```dotenv
POSTGRES_PASSWORD=请替换为随机数据库密码
MINDTRAIN_BOOTSTRAP_TOKEN=请替换为随机长令牌
```

可以使用 `openssl rand -hex 32` 生成随机值。`MINDTRAIN_BOOTSTRAP_TOKEN` 同时保护 Codex → Trainer MCP 和 Trainer MCP → Core，请勿提交或公开。

### 2. 启动 MindTrain

```bash
docker compose up -d --build
docker compose ps
```

等待四个容器健康后访问：

- Web：[http://127.0.0.1:4173](http://127.0.0.1:4173)
- Core 健康检查：[http://127.0.0.1:8080/actuator/health](http://127.0.0.1:8080/actuator/health)
- MCP 健康检查：[http://127.0.0.1:8787/actuator/health](http://127.0.0.1:8787/actuator/health)

PostgreSQL 不对宿主机开放；Core、MCP 和 Web 默认只监听 `127.0.0.1`。

### 3. 安装 Codex Plugin

```bash
codex plugin marketplace add shigella520/MindTrain --ref main
codex plugin add mindtrain@mindtrain
```

安装完成后新建一个 Codex 任务，输入：

```text
$mindtrain 配置我的 MindTrain 实例
```

按提示提供：

1. MCP 地址。本机部署使用 `http://127.0.0.1:8787/mcp`；云端部署使用自己的 HTTPS 地址。
2. `.env` 中的 `MINDTRAIN_BOOTSTRAP_TOKEN`。

配置只保存在本机用户配置目录，不进入项目仓库。完整安装、升级和故障排查见 [Codex Plugin 部署](doc/CodexPlugin部署.md)。

### 4. 开始第一次训练

新建一个任务并输入：

```text
$mindtrain 开始训练
```

你可以在任何一道题中直接问：

```text
这个概念具体是什么？
为什么 B 不对？
先给我一个提示，不要公布答案。
这道题不适合当前主题，换一道。
```

只有明确提交选项时才会产生 Attempt；普通追问不会消耗题目。

也可以直接通过对话创建训练领域：

```text
使用 $mindtrain，根据我的学习目标创建一个 Kubernetes 训练领域。先展示完整知识点树，等我确认后再保存。
```

或者从本地资料创建：

```text
$mindtrain 使用 /path/to/notes 创建 backend-notes 资料库，
为我整理训练领域和知识点，预览确认后保存并开始训练
```

Plugin 会在本机缓存目录建立私有解析环境和全文索引。Codex 根据你选择的资料整理训练领域和知识点，经你确认后保存到 Core；Core 不替你决定学习内容，也不接收文件原文或绝对路径。PDF 首期只抽取文本，不做 OCR。

## 云端私有部署

MindTrain 面向单用户私有部署。推荐只通过反向代理公开 Web 和 Trainer MCP：

```text
https://mindtrain.example.com/     -> http://127.0.0.1:4173/
https://mindtrain.example.com/mcp -> http://127.0.0.1:8787/mcp
```

云端 MCP 必须使用 HTTPS 和 Bearer Token。不要公开 PostgreSQL，也没有必要把 Core 端口暴露到公网。

Nginx/Caddy/Cloudflare Tunnel、健康检查、备份恢复、升级和完整配置说明见[部署与运维](doc/部署与运维.md)。

## 常用操作

```bash
# 查看状态和日志
cd deploy/core-only
docker compose ps
docker compose logs -f trainer-core trainer-mcp web

# 停止服务但保留数据
docker compose down

# 更新当前源码并重建
git pull
docker compose up -d --build
```

训练题量、新题额度、积压阈值、临时题有效期和报表时区可在 Web 的“管理 → 训练配置”中修改，不需要重启服务。

## 文档导航

| 想做什么 | 阅读 |
| --- | --- |
| 部署到服务器、配置反向代理、备份或升级 | [部署与运维](doc/部署与运维.md) |
| 安装、切换分支、更新或排查 Codex Plugin | [Codex Plugin 部署](doc/CodexPlugin部署.md) |
| 本地开发、运行测试、构建镜像或了解 CI | [开发指南](doc/开发指南.md) |
| 了解 Dashboard、Web 训练和视觉设计 | [Web 设计](doc/Web设计.md) |
| 创建、查询领域与知识点树 | [知识目录](doc/知识目录.md) |
| 查看 REST API | [Training Core OpenAPI](contracts/openapi/trainer-core.yaml) |
| 查看产品目标、架构与仓库边界 | [目标需求](doc/目标需求.md) · [概要设计](doc/概要设计.md) · [仓库目录规划](doc/仓库目录规划.md) |
| 查看 Skill 工作流 | [MindTrain Plugin Skill](plugins/mindtrain/skills/mindtrain/SKILL.md) |

## 路线图

- 扩充领域无关 Knowledge Pack 和内容管理能力。
- 完善 Web 题库治理、近期会话和训练洞察。
- 接入 Anki Bridge 与 FSRS Scheduler Provider。
- 增加备份恢复、导入导出和多用户能力。

欢迎通过 Issue 提交使用反馈、题目质量问题和新的知识领域建议。

## 许可证

MindTrain 使用 [MIT License](LICENSE) 开源，可自由使用、修改、分发和商用，但需保留原始版权声明和许可证文本。

## 友情链接

<p align="center">
  <a href="https://linux.do" target="_blank">
    <img src="https://img.shields.io/badge/LINUX-DO-FFB003?style=for-the-badge&logo=linux&logoColor=white" alt="LINUX DO" />
  </a>
</p>
