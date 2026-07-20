# MindTrain

[![CI](https://github.com/shigella520/MindTrain/actions/workflows/ci.yml/badge.svg)](https://github.com/shigella520/MindTrain/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)

MindTrain 是一个面向 Codex 和其他 AI 客户端的知识训练平台。它把对话式讲解、确定性判分、题库治理和抗遗忘调度组合在同一条训练链路中，让用户可以在答题过程中随时追问，又不丢失可审计的学习记录。

当前版本已提供可由 Codex 使用的 MVP：`Training Core + Trainer MCP + PostgreSQL + MindTrain Skill`。Java 后端面试知识是首个领域原型，但平台核心不绑定 Java。

```text
Codex + MindTrain Plugin         MindTrain Web
              |                      |
              | local bridge MCP     | REST
              v                      v
     Private Trainer MCP ------ Training Core -------- PostgreSQL
              |
              +-- Weighted Scheduler（当前可用）
              +-- Anki / FSRS Provider（规划中）
```

## 功能特点

- 对话式训练：在 Codex 中完成出题、回答、追问、提示、深入讲解和会话总结，无需切换到其他客户端。
- 安全出题：正式出题接口只返回题干和选项，提交答案前不返回正确答案、解释或可能泄露答案组合的提示。
- 精确判分：单选和多选都按选项集合完全相等判分，结果只为 `0` 或 `100`。
- 持久学习记录：保存 Session、Assignment、Attempt、Interaction、Mistake 和题目级复习状态。
- 默认抗遗忘调度：每次默认训练 10 道主问题，通常安排 8 道复习题和 2 道新题；积压过高时暂停引入新题。
- AI 候选题治理：题库不足时由 Codex 生成候选题，Core 校验后仅允许当前 Session 使用，不会自动发布到共享题库。
- 幂等与审计：写接口支持 `Idempotency-Key`，答题、交互和审核历史采用追加式保存。
- 数据与 Skill 分离：Training Core 是权威数据源；Skill 无状态，只通过 MCP 调用应用能力。
- Plugin 分发：仓库可直接作为 Codex Plugin Marketplace 来源，Plugin 同时提供 Skill、本地桥接 MCP 和首次配置流程。
- Web 训练：提供学习 Dashboard、选择题训练、实例设置和内容治理概览，响应式覆盖桌面与手机。
- 调度器可插拔：Core 已建立 Scheduler SPI，当前提供加权调度器，后续可接入 Anki/FSRS。
- 原型可迁移：提供默认 dry-run 的迁移工具，将现有 Java 题库和可选的私人学习历史导入 Core。

## 当前状态

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| Training Core | 可用 | Spring Boot 模块化单体、REST API、PostgreSQL、Flyway |
| Trainer MCP | 可用 | Streamable HTTP MCP，默认监听 `127.0.0.1:8787/mcp` |
| Codex Plugin Marketplace | 可用 | 安装后首次配置私有实例 URL 和单用户 Token |
| MindTrain Skill | 可用 | 领域无关、无状态、面向 Codex 的训练工作流 |
| Weighted Scheduler | 可用 | 复习/新题配额、积压控制、薄弱项优先 |
| Java 原型迁移 | 可用 | 正式题默认导入，私人数据必须显式选择 |
| MindTrain Web | 可用 | Vue 3 Dashboard、Web 答题、管理概览与实例设置 |
| Anki / FSRS Provider | 规划中 | Anki 作为可选调度插件和可重建投影 |

## 安装（Docker Compose）

### 1. 准备配置

```bash
cd deploy/core-only
cp .env.example .env
```

至少修改 `.env` 中的以下配置：

```dotenv
POSTGRES_PASSWORD=replace-with-a-database-password
MINDTRAIN_BOOTSTRAP_TOKEN=replace-with-a-long-random-token
```

`MINDTRAIN_BOOTSTRAP_TOKEN` 是单用户部署令牌，同时保护 Codex → Trainer MCP 和 Trainer MCP → Core。请使用随机长字符串，不要把真实令牌提交到仓库。

### 2. 启动服务

使用当前源码构建并启动：

```bash
docker compose up -d --build
```

默认只映射到宿主机 loopback：

| 服务 | 地址 | 用途 |
| --- | --- | --- |
| Training Core | `http://127.0.0.1:8080` | 权威数据、训练、调度与报告 API |
| Trainer MCP | `http://127.0.0.1:8787/mcp` | Codex 和其他 MCP 客户端入口 |
| MindTrain Web | `http://127.0.0.1:4173` | 学习看板、Web 训练与管理概览 |
| PostgreSQL | 不对宿主机开放 | 保存题库、会话、学习历史和调度状态 |

健康检查：

```bash
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8787/actuator/health
curl http://127.0.0.1:4173/healthz
```

停止服务：

```bash
docker compose down
```

PostgreSQL 数据默认保存在仓库根目录的 `var/postgres/`。删除或迁移该目录前请先备份数据库。

### 3. 云端暴露 Trainer MCP

Training Core 和 PostgreSQL 应继续保持在 loopback 或容器私网中。通过 Nginx、Caddy、Cloudflare Tunnel 或同类入口暴露 Web 和 Trainer MCP：

```text
https://mindtrain.example.com/     -> http://127.0.0.1:4173/
https://mindtrain.example.com/mcp -> http://127.0.0.1:8787/mcp
```

Trainer MCP 会验证：

```http
Authorization: Bearer <MINDTRAIN_BOOTSTRAP_TOKEN>
```

未提供 Token 或 Token 错误时返回 `401 Unauthorized`。健康检查端点不要求 Token，便于容器和反向代理探活。

## 接入 Codex Plugin

### 1. 添加 Marketplace 来源

MindTrain 仓库包含 [`.agents/plugins/marketplace.json`](.agents/plugins/marketplace.json)，可以直接作为 Codex Marketplace 来源：

```bash
codex plugin marketplace add shigella520/MindTrain --ref main
```

开发版本可以显式选择 `dev`：

```bash
codex plugin marketplace add shigella520/MindTrain --ref dev
```

### 2. 安装 Plugin

在 Codex CLI 中安装：

```bash
codex plugin add mindtrain@mindtrain
```

也可以打开 Codex 的 `/plugins`，选择 `MindTrain` Marketplace 后安装。安装完成后开启一个新任务，使 bundled Skill 和本地 MCP bridge 生效。

### 3. 首次使用配置私有实例

在新任务中调用：

```text
$mindtrain Configure my private MindTrain instance.
```

Skill 会先调用 `get_mindtrain_configuration`。尚未配置时，它会依次询问：

1. 私有 Trainer MCP 完整 HTTPS 地址，例如 `https://mindtrain.example.com/mcp`。
2. 部署时设置的 `MINDTRAIN_BOOTSTRAP_TOKEN`。

本地 bridge 会使用该 Token 请求远程 `initialize`，验证地址、Token 和服务身份后，将配置保存到用户配置目录：

```text
~/.config/mindtrain/plugin.json
```

该文件使用仅当前用户可读写的权限，不进入项目仓库；状态查询也不会返回 Token。之后可以直接使用 `$mindtrain` 开始训练。

如果不希望在 Codex 对话中输入 Token，可以在已安装 Plugin 目录执行交互式配置：

```bash
python3 scripts/mindtrain_mcp_bridge.py --configure
```

### 手动 MCP 配置（备用）

不使用 Plugin 时，可以参考 [`deploy/core-only/codex-config.toml.example`](deploy/core-only/codex-config.toml.example) 手动连接：

```toml
[mcp_servers.mindtrain]
url = "https://mindtrain.example.com/mcp"
bearer_token_env_var = "MINDTRAIN_BOOTSTRAP_TOKEN"
```

这种方式仍需单独安装或加载 [`skills/mindtrain`](skills/mindtrain)，推荐普通用户优先使用 Plugin。

一个典型会话如下：

```text
用户：开始练习 JVM

Codex：创建训练会话并从 Core 获取下一题
Codex：展示题干和 A-D 选项
Codex：请回复选项字母，可用逗号分隔。

用户：这个选项里的“方法区”具体是什么？

Codex：记录 Interaction，解释概念，当前题目保持未提交

用户：AC

Codex：向 Core 提交答案，返回判分、正确答案和结构化解释
Codex：提供“下一题、深入追问、结束总结”
```

普通提问、质疑和索要提示只会记录为 Interaction，不会消耗当前题目；只有明确提交选项后才会产生 Attempt。

## 核心训练流程

```text
创建 Session（默认 10 道主问题）
        |
        v
调度器规划复习题与新题配额
        |
        v
Core 创建 Assignment，只返回题干与选项
        |
        +-------------------------------+
        |                               |
        v                               v
用户提交选项                       用户提问或索要提示
        |                               |
        v                               v
Core 精确判分                     追加 Interaction
        |                               |
        v                               +-- 当前题保持待答
追加 Attempt / Mistake / Review State
        |
        v
返回正确答案与结构化解释
        |
        v
下一题 / 深入追问 / 结束总结
```

当没有可用正式题时，Core 返回 `generation_required` 和目标知识点上下文。Codex 生成候选题并交给 Core 校验；通过后的候选题只能在生成它的 Session 中训练，不能被其他会话抽取，也不会自动成为正式题。

## 默认调度策略

默认 `weighted` 调度器以可控的每日训练容量为优先：

- 每个 Session 默认包含 10 道主问题。
- 正常状态最多安排 8 道复习题和 2 道新题。
- 到期复习题不足时，才使用新题补足剩余额度。
- Due Backlog 超过 20，或最老题目逾期超过 3 天时，暂停引入新题。
- Learning、Relearning、严重逾期、薄弱和高错误率题目优先。
- 正确后的初始复习间隔采用 3、7、14、30 天阶梯；错误后次日复习。

这些规则用于避免“每天只能完成 10 题，但新增内容持续制造更多到期复习”的失控状态。调度实现位于 Core 内部，并通过 Scheduler SPI 为后续 Anki/FSRS Provider 保留替换边界。

## 导入 Java 原型数据

迁移工具默认执行 dry-run，并只处理共享知识分类、来源和正式题：

```bash
python3 tools/prototype-migration/import_prototype.py \
  --token "$MINDTRAIN_BOOTSTRAP_TOKEN"
```

检查数量、冲突、跳过项和数据哈希后，使用 `--apply` 持久化：

```bash
python3 tools/prototype-migration/import_prototype.py \
  --token "$MINDTRAIN_BOOTSTRAP_TOKEN" \
  --apply
```

私人候选题和学习历史不会默认导入，必须显式选择：

```bash
python3 tools/prototype-migration/import_prototype.py \
  --token "$MINDTRAIN_BOOTSTRAP_TOKEN" \
  --include-private-candidates \
  --include-learning-data \
  --apply
```

迁移工具只读取原型 JSON/JSONL，不会修改原始文件；稳定 ID、版本和数据哈希用于保证重复执行时可对账。

## API 与 MCP 工具

Training Core 首期 REST API：

| 接口 | 用途 |
| --- | --- |
| `POST /api/v1/sessions` | 创建训练会话 |
| `POST /api/v1/sessions/{id}/assignments/next` | 获取下一题或生成需求 |
| `POST /api/v1/assignments/{id}/attempts` | 提交选项并精确判分 |
| `POST /api/v1/sessions/{id}/interactions` | 记录追问、提示和质疑 |
| `POST /api/v1/sessions/{id}/finish` | 结束会话并生成总结 |
| `POST /api/v1/candidates` | 校验并保存当前会话候选题 |
| `GET /api/v1/reports/overview` | 获取学习概览 |
| `GET /api/v1/schedulers/backlog` | 获取到期积压 |
| `POST /api/v1/imports/prototype` | 创建原型导入任务 |
| `GET /api/v1/imports/{id}` | 查询导入结果 |

完整契约见 [`contracts/openapi/trainer-core.yaml`](contracts/openapi/trainer-core.yaml)。

Trainer MCP 当前提供：

```text
create_training_session
get_next_assignment
submit_choice_answer
record_interaction
create_candidate_question
finish_training_session
get_learning_report
get_scheduler_backlog
```

MCP 只调用 Core API，不直接访问 PostgreSQL。

## 配置项

Core-only Compose 常用配置：

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `POSTGRES_DB` | `mindtrain` | PostgreSQL 数据库名 |
| `POSTGRES_USER` | `mindtrain` | PostgreSQL 用户名 |
| `POSTGRES_PASSWORD` | 无安全默认值 | PostgreSQL 密码，部署时必须修改 |
| `MINDTRAIN_BOOTSTRAP_USER_ID` | `local-admin` | 启动时创建的本地管理员 ID |
| `MINDTRAIN_BOOTSTRAP_DISPLAY_NAME` | `Local Admin` | 本地管理员显示名 |
| `MINDTRAIN_BOOTSTRAP_TOKEN` | 无安全默认值 | Core Bearer Token，MCP 使用同一令牌访问 Core |
| `MINDTRAIN_CORE_PORT` | `8080` | Core 映射到宿主机的端口 |
| `MINDTRAIN_MCP_PORT` | `8787` | MCP 映射到宿主机的端口 |

Core 还支持下列调度配置：

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `MINDTRAIN_REVIEW_BUDGET` | `8` | 单次会话最大复习题数量 |
| `MINDTRAIN_NEW_BUDGET` | `2` | 单次会话最大新题数量 |
| `MINDTRAIN_BACKLOG_PAUSE_THRESHOLD` | `20` | 暂停新题的到期积压阈值 |
| `MINDTRAIN_OVERDUE_PAUSE_DAYS` | `3` | 暂停新题的最老逾期天数阈值 |

## 项目结构

```text
MindTrain/
├── apps/
│   ├── trainer-core/             # 权威业务数据、判分、调度、报告 REST API
│   ├── trainer-mcp/              # 面向 Codex/AI 客户端的 MCP Gateway
│   └── web/                      # Vue Dashboard、训练页、管理概览与 Nginx 镜像
├── plugins/
│   └── mindtrain/                # Codex Plugin、首次配置 bridge 和 bundled Skill
├── skills/
│   └── mindtrain/                # 领域无关、无状态的 AI 教练 Skill
├── contracts/
│   ├── openapi/                  # Training Core REST 契约
│   └── scheduler-spi/            # 调度器 Provider 契约
├── deploy/
│   └── core-only/                # Core、MCP、PostgreSQL 本地部署
├── tools/
│   └── prototype-migration/      # 原型迁移与数量对账工具
├── doc/                          # 需求、概要设计、仓库和 Web 设计文档
├── assets/                       # 迁移期 Java 原型知识资产
├── learning-data/                # 迁移期私人学习数据，不应进入新增提交
├── skill/java-interview-coach/   # 迁移期兼容 Skill
├── .github/workflows/            # 验证与 GHCR 镜像发布
├── .agents/plugins/              # Codex Marketplace 清单
└── pom.xml                       # Java 21 Maven 多模块入口
```

依赖边界：

- 只有 Training Core 可以直接访问业务数据库。
- Trainer MCP、Web 和未来 Anki Bridge 只能通过 Core API 访问权威数据。
- Skill 只编排 MCP 工具，不保存题库、会话和学习记录。
- Plugin bridge 只保存私有实例 URL 和 Token，且保存在用户配置目录而非仓库。
- Anki 是可选调度插件和可重建投影，不是 MindTrain 主数据库。
- 生产运行数据写入 PostgreSQL 或 `var/`，不得提交到仓库。

## 本地开发

要求：

- Java 21
- Maven 3.9+
- Python 3.12+（原型校验与迁移工具）
- Docker / Docker Compose（集成部署）
- Node.js 22 与 pnpm 10（Web 开发）

验证平台代码：

```bash
mvn -B verify
```

验证 Java 原型、数据协议和 Skills：

```bash
make bootstrap
make check
```

Core REST 契约变更应同步更新 OpenAPI；Skill 或 MCP 工具变更应保持中性答题提示，不能在提交答案前泄露正确选项数量或组合。

Web 本地开发：

```bash
cd apps/web
pnpm install
pnpm dev
```

Web 测试与生产构建：

```bash
pnpm test
pnpm build
```

## Docker 镜像发布

GitHub Actions 构建三个 GHCR 镜像：

- `ghcr.io/shigella520/mindtrain-core`
- `ghcr.io/shigella520/mindtrain-mcp`
- `ghcr.io/shigella520/mindtrain-web`

发布规则：

- Pull Request 到 `main`：运行 Java、Web、原型和 Skill 验证，并试构建三个镜像，不推送。
- 推送到 `main`：验证通过后发布 `latest` 和提交 SHA 标签。
- `dev`：手动运行 `Publish Dev Images`，且必须选择 `dev` 分支，发布 `dev` 和提交 SHA 标签。

## 路线图

1. 完成 Codex MVP 的真实 PostgreSQL 部署验证和训练反馈闭环。
2. 扩充领域无关 Knowledge Pack，并完成 Java 原型数据迁移演练。
3. 补充题库查询、候选审核和近期会话 API，完成 Web 内容管理闭环。
4. 实现 MindTrain Anki Bridge，并接入 FSRS Scheduler Provider。
5. 完成多用户、备份恢复、导入导出和插件治理能力。

## 文档

- [目标需求](doc/目标需求.md)
- [概要设计](doc/概要设计.md)
- [仓库目录规划](doc/仓库目录规划.md)
- [Web 设计](doc/Web设计.md)
- [Training Core OpenAPI](contracts/openapi/trainer-core.yaml)
- [MindTrain Skill](skills/mindtrain/SKILL.md)
- [Codex Plugin 部署](doc/CodexPlugin部署.md)

## 许可证

MindTrain 使用 [MIT License](LICENSE) 开源。

这意味着允许自由使用、修改、分发和商用，但需要保留原始版权声明和许可证文本。
