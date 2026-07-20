# MindTrain

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

仓库正从 `java-interview-coach` Codex Skill 原型转型为 MindTrain 平台。

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

## 演进路线

1. 建立 `apps/trainer-core`、`contracts`、`database` 和 Core-only 部署骨架。
2. 实现知识资产、训练会话和 Attempt API。
3. 迁移原型文件数据并完成数量对账。
4. 建立 Scheduler SPI、默认加权调度和容量控制。
5. 建立 Trainer MCP 与领域无关 Knowledge Trainer Skill。
6. 验证现成 Anki MCP，再实现 MindTrain Anki Bridge。
7. 清理生产文件数据，提供 Web、Knowledge Pack 和完整部署指引。
