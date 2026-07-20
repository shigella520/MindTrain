# MindTrain

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

MindTrain 是一个 AI 驱动、调度算法可插拔、领域数据可独立管理的知识训练平台。

平台目标包括：

- 提供可独立部署的知识训练应用和 API。
- 通过 AI Skill 与 Codex 集成，保留可随时追问的对话式训练体验。
- 管理题库、知识分类、来源、Prompt、候选题、会话和学习记录。
- 提供默认加权调度算法，并允许通过插件接入 Anki/FSRS。
- 支持多个领域知识包；Java 后端面试知识是首个领域原型。

详细文档：

- [目标需求](doc/目标需求.md)
- [概要设计](doc/概要设计.md)
- [仓库目录规划](doc/仓库目录规划.md)

## 目标架构

```text
Codex Skill / Web App
          ↓
      Trainer MCP
          ↓
     Training Core ───── PostgreSQL
          │
          ├─ 默认加权调度器
          └─ Scheduler SPI
                    ↓
             Anki Bridge Add-on
                    ↓
              Anki Scheduler/FSRS
```

Training Core 是题库和学习数据的权威来源。Anki 是可选的调度插件和本地数据投影，不是 MindTrain 的主数据库。

## 目标仓库结构

```text
MindTrain/
├─ apps/
│  ├─ trainer-core/            # Spring Boot 模块化单体
│  ├─ trainer-mcp/             # Codex/AI MCP Gateway
│  └─ web/                     # 学习端与内容管理端
├─ integrations/
│  └─ anki/                    # Add-on、Bridge Contract、兼容测试
├─ skills/
│  ├─ knowledge-trainer/       # 领域无关 AI 教练 Skill
│  └─ java-interview-coach/    # 迁移期兼容 Skill
├─ contracts/                  # OpenAPI、事件、Knowledge Pack、Scheduler SPI
├─ database/                   # 数据库迁移与匿名测试 Fixture
├─ deploy/                     # Core-only、Anki 本地和私有同步部署
├─ tools/                      # 原型迁移、导入导出和开发工具
├─ tests/                      # 契约、集成、E2E 和 AI Eval
├─ prototype/                  # 数据迁移完成前的原型快照
└─ doc/
```

依赖规则：

- 只有 Training Core 可以直接访问业务数据库。
- Trainer MCP、Web 和 Anki Bridge 只能通过 Core API 使用权威数据。
- Skill 只调用 MCP，不保存题库和用户记录。
- Anki 专有类型不能进入 Core 领域模型。
- 生产运行数据写入数据库或本地 `var/`，不得提交到仓库。

详细迁移映射和分阶段调整见[仓库目录规划](doc/仓库目录规划.md)。

## 当前状态

仓库已经包含第一版 Codex 可用 MVP：

- `apps/trainer-core`：Spring Boot + PostgreSQL 权威数据与训练 API。
- `apps/trainer-mcp`：监听 `127.0.0.1:8787/mcp` 的 Streamable HTTP MCP Gateway。
- `skills/knowledge-trainer`：无状态、只调用 MCP 的领域无关 AI 教练 Skill。
- `tools/prototype-migration`：默认 dry-run 的原型导入和数量对账工具。
- `deploy/core-only`：Core、MCP、PostgreSQL 的本地 Docker Compose。

Java Interview Coach 文件原型仍保留，用于迁移验证和兼容已有学习数据。

当前可运行原型仍位于：

```text
skill/java-interview-coach/
```

现有目录中的数据用于验证过的第一轮训练和后续迁移：

- `assets/`：原型正式题、候选题、知识点和来源。
- `learning-data/`：原型答题历史、掌握度、错题和会话。
- `prompts/`：原型 AI Prompt。
- `schemas/`：原型数据协议。

这些文件目前继续保留且不得静默改写。目标架构会把运行数据迁移到 Training Core + PostgreSQL，仓库最终只保留应用代码、迁移、契约、部署配置和测试 Fixture。

目录迁移遵循“先建立 Core 和契约、再迁移数据、最后清理原型”的顺序。当前阶段不会移动这些目录，以免同时改变文件路径和业务行为。

## 原型检查

在平台核心实现前，原型仍可使用：

```bash
make check
```

创建原型训练会话：

```bash
.skill-venv/bin/python skill/java-interview-coach/scripts/new-session.py
.skill-venv/bin/python skill/java-interview-coach/scripts/select-question.py
```

在 Codex 中可以继续使用 `$java-interview-coach` 完成 Java 面试训练。该 Skill 后续会迁移为调用 Trainer MCP 的领域适配层。

## 启动 Codex MVP

复制部署环境变量并设置随机令牌：

```bash
cd deploy/core-only
cp .env.example .env
# 修改 .env 中的数据库密码和 MINDTRAIN_BOOTSTRAP_TOKEN
docker compose up -d --build
```

健康检查：

```bash
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8787/actuator/health
```

把 `deploy/core-only/codex-config.toml.example` 中的 MCP 配置合并到 Codex 配置：

```toml
[mcp_servers.mindtrain]
url = "http://127.0.0.1:8787/mcp"
```

然后通过 `$knowledge-trainer` 开始训练。正式答案统一使用中性提示“请回复选项字母，可用逗号分隔”，普通提问和追问会作为 Interaction 保存，不会消耗当前题目。

## 导入原型数据

默认执行 dry-run，只导入共享分类和正式题：

```bash
python3 tools/prototype-migration/import_prototype.py \
  --token "$MINDTRAIN_BOOTSTRAP_TOKEN"
```

确认报告后使用 `--apply` 持久化。私人候选题和学习历史必须显式选择：

```bash
python3 tools/prototype-migration/import_prototype.py \
  --token "$MINDTRAIN_BOOTSTRAP_TOKEN" \
  --include-private-candidates \
  --include-learning-data \
  --apply
```

导入工具只读取原型文件，不修改 JSON/JSONL。

## 平台验证

生产目标为 Java 21：

```bash
mvn test
```

当前原型检查仍使用：

```bash
make check
```

Core REST 契约见 `contracts/openapi/trainer-core.yaml`。Web 视觉与 LinkPeek 设计语言的复用说明见 `doc/Web设计.md`。

## Docker 镜像发布

GitHub Actions 发布两个 GHCR 镜像：

- `ghcr.io/shigella520/mindtrain-core`
- `ghcr.io/shigella520/mindtrain-mcp`

向 `main` 推送时，在全部 Java、原型和 Skill 检查通过后自动发布 `latest` 与提交 SHA 标签。Pull Request 只执行验证和镜像试构建，不推送镜像。

`dev` 镜像通过 GitHub Actions 的 `Publish Dev Images` 手动运行，运行时必须选择 `dev` 分支；成功后发布 `dev` 与提交 SHA 标签。

## 演进路线

1. 完善 Codex MVP 的真实 PostgreSQL 部署和用户试用反馈。
2. 扩充 Java Knowledge Pack，并完成私人历史迁移演练。
3. 实现 Vue 3 混合 Dashboard 和内容管理页面。
4. 验证现成 Anki MCP，再实现 MindTrain Anki Bridge。
5. 清理生产文件数据并完善 Knowledge Pack 导入导出。

## License

MindTrain 基于 [MIT License](LICENSE) 开源。
