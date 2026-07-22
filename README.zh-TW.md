<p align="center">
  <img src="assets/brand/mindtrain-icon.png" width="128" alt="MindTrain 圖示" />
</p>

<h1 align="center">MindTrain</h1>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <strong>繁體中文</strong>
</p>

<p align="center">
  <strong>你的私人 AI 知識訓練場：邊問、邊練、邊複習，把一次對話變成長期記憶。</strong>
</p>

<p align="center">
  <a href="https://mindtrain.jianyutan.com/">線上演示</a> ·
  <a href="#5-分鐘開始使用">快速開始</a> ·
  <a href="doc/CodexPlugin部署.md">Codex Plugin</a> ·
  <a href="doc/部署与运维.md">部署與運維</a>
</p>

<p align="center">
  <a href="https://github.com/shigella520/MindTrain/actions/workflows/ci.yml"><img src="https://github.com/shigella520/MindTrain/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="MIT License" /></a>
  <a href="https://openjdk.org/projects/jdk/21/"><img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21" /></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.5" /></a>
</p>

MindTrain 是一個面向 Codex 和其他 AI 客戶端的開源知識訓練平臺。你可以像普通聊天一樣隨時追問、質疑和索要提示；MindTrain 在後臺負責題庫、精確判分、學習記錄和抗遺忘排程。

- **對話不中斷**：遇到不懂的概念直接問，當前題目不會被誤判為已作答。
- **資料歸自己**：單使用者私有部署，題庫和學習歷史儲存在自己的 PostgreSQL 中。
- **真正形成閉環**：複習舊題、按需生成新題、記錄錯誤，並控制每天的新題與到期積壓。
- **知識目錄初始化**：既可透過 AI 對話規劃領域與知識點，也可指定本地資料目錄整理知識樹；完整預覽並確認後才儲存。

MindTrain 核心不繫結具體知識領域；每個私有例項可以獨立管理自己的領域、知識點和題庫。

## 使用預覽

### 如何工作

![MindTrain 內部架構](doc/architecture-overview.svg)

- Training Core 是題目、會話、作答、複習狀態和配置的權威資料來源。
- Trainer MCP 只呼叫 Core API，不直接訪問資料庫。
- Skill 負責教學和工具編排，不儲存業務資料。
- AI 臨時題會先校驗併入庫再展示；作答前可拒絕並物理刪除，作答後轉為普通複習題。

設計決策和資料邊界見[概要設計](doc/概要设计.md)。

### 訓練流程

![MindTrain 訓練流程](doc/training-flow.svg)

一次訓練並不是簡單的“顯示答案”：排程器先根據到期狀態和新題額度選題；使用者可以在提交前無限追問；只有明確提交選項後，Core 才會精確判分並追加學習記錄。題庫不足時，Core 會返回結構化生成要求，由 Codex 生成、Core 校驗入庫後再展示。

每輪題量、複習與新題配額、積壓暫停規則和 AI 臨時題有效期均由資料庫配置控制，可在 Web 管理頁調整。

### 在 Codex 中訓練、追問和複習

![MindTrain Codex 對話式訓練示例](doc/preview-codex-training.png)

線上演示：[https://mindtrain.jianyutan.com/](https://mindtrain.jianyutan.com/)（可公開檢視示例統計；訓練需要連線自己的私有例項）

## 為什麼使用 MindTrain

| 能力 | 你得到什麼 |
| --- | --- |
| 對話式 AI 教練 | 在同一題中追問概念、索要提示、質疑答案，再繼續作答 |
| 確定性判分 | 單選和多選按選項集合精確判分，不讓模型“憑感覺”決定分數 |
| 抗遺忘排程 | 預設平衡複習題和新題；積壓過高時自動暫停新題 |
| AI 補題 | 題庫不足時由 Codex 按指定知識點、題型和難度生成並校驗新題 |
| 本地資料庫 | 支援 MD、TXT、PDF、DOCX、PPTX；原文和索引只留在本機 |
| 知識目錄 | Codex 與 Web 均可查詢多個領域、知識點樹、關鍵詞、題目覆蓋和掌握度 |
| 私有資料 | Core 是唯一權威資料來源；Skill 無狀態，倉庫不承載執行資料 |
| Web + Codex | Web 用於看板、舊題複習和配置；Codex 用於完整的 AI 教練體驗 |

當前可用：Training Core、Trainer MCP、MindTrain Web、Codex Plugin 和加權排程。Anki / FSRS Provider 尚在規劃中。

## 5 分鐘開始使用

### 前置條件

- Git
- Docker 與 Docker Compose
- Codex CLI 或 Codex App（使用 AI 教練時需要）

### 1. 安裝並啟動 MindTrain

```bash
git clone https://github.com/shigella520/MindTrain.git
cd MindTrain/deploy/core-only
cp .env.example .env
```

編輯 `.env`，至少替換下面兩項：

```dotenv
POSTGRES_PASSWORD=請替換為隨機資料庫密碼
MINDTRAIN_BOOTSTRAP_TOKEN=請替換為隨機長令牌
```

可以使用 `openssl rand -hex 32` 生成隨機值。`MINDTRAIN_BOOTSTRAP_TOKEN` 同時保護 Codex → Trainer MCP 和 Trainer MCP → Core，請勿提交或公開。

```bash
docker compose up -d --build
docker compose ps
```

等待四個容器健康後訪問：

- Web：[http://127.0.0.1:4173](http://127.0.0.1:4173)
- Core 健康檢查：[http://127.0.0.1:8080/actuator/health](http://127.0.0.1:8080/actuator/health)
- MCP 健康檢查：[http://127.0.0.1:8787/actuator/health](http://127.0.0.1:8787/actuator/health)

PostgreSQL 不對宿主機開放；Core、MCP 和 Web 預設只監聽 `127.0.0.1`。

### 2. 配置 Codex Plugin

先安裝 MindTrain Plugin：

```bash
codex plugin marketplace add shigella520/MindTrain --ref main
codex plugin add mindtrain@mindtrain
```

安裝完成後新建一個 Codex 任務，輸入：

```text
$mindtrain 配置我的 MindTrain 例項
```

按提示提供：

1. MCP 地址。本機部署使用 `http://127.0.0.1:8787/mcp`；雲端部署使用自己的 HTTPS 地址。
2. `.env` 中的 `MINDTRAIN_BOOTSTRAP_TOKEN`。

配置只儲存在本機使用者配置目錄，不進入專案倉庫。完整安裝、升級和故障排查見 [Codex Plugin 部署](doc/CodexPlugin部署.md)。

### 3. 建立訓練領域

第一次訓練前，先告訴 MindTrain 你想學習什麼。一個私有例項可以管理多個訓練領域，每個領域可以包含多棵知識點樹。

最簡單的方式是讓 Codex 根據學習目標規劃領域：

```text
使用 $mindtrain，根據【我的學習目標】建立一個訓練領域。請先展示完整知識點樹，等我確認後再儲存。
```

使用前請把 `【我的學習目標】` 替換為具體內容，例如“準備 Kubernetes 運維面試，重點掌握 Pod、Deployment、Service 和故障排查”。如果沒有提供具體目標，MindTrain 會先詢問學習主題、範圍和期望深度，再生成知識點樹。

如果已有自己的學習資料，也可以指定本地目錄：

```text
$mindtrain 使用 /path/to/notes 建立 backend-notes 資料庫，
為我整理訓練領域和知識點，預覽確認後儲存
```

無論使用哪種方式，Codex 都會先展示完整的領域資訊和知識點樹；只有你明確確認後，MindTrain 才會原子寫入 Core。透過本地資料建立時，Plugin 會在本機快取目錄建立私有解析環境和全文索引，Core 不接收檔案原文、絕對路徑或本地索引。PDF 首期只抽取文字，不做 OCR。

建立完成後，可以在 Web 的“知識目錄”中瀏覽知識樹、搜尋知識點並檢視題目覆蓋和掌握度。詳細說明見[知識目錄](doc/知识目录.md)。

### 4. 開始第一次訓練

新建一個任務並輸入：

```text
$mindtrain 開始訓練
```

只有一個訓練領域時會自動使用；存在多個領域時，Codex 或 Web 會要求你選擇本次訓練的領域，例如 `$mindtrain 開始 ai-agent 訓練`。一次訓練 Session 只會從所選領域出題，不會在多個領域之間隨機混合。

你可以在任何一道題中直接問：

```text
這個概念具體是什麼？
為什麼 B 不對？
先給我一個提示，不要公佈答案。
這道題不適合當前主題，換一道。
```

只有明確提交選項時才會產生 Attempt；普通追問不會消耗題目。

## 雲端私有部署

MindTrain 面向單使用者私有部署。推薦只透過反向代理公開 Web 和 Trainer MCP：

```text
https://mindtrain.example.com/     -> http://127.0.0.1:4173/
https://mindtrain.example.com/mcp -> http://127.0.0.1:8787/mcp
```

雲端 MCP 必須使用 HTTPS 和 Bearer Token。不要公開 PostgreSQL，也沒有必要把 Core 埠暴露到公網。

Nginx/Caddy/Cloudflare Tunnel、健康檢查、備份恢復、升級和完整配置說明見[部署與運維](doc/部署与运维.md)。

## 升級 MindTrain

升級分為服務端和 Codex Plugin 兩部分。升級前先備份 PostgreSQL；訓練領域、題庫、作答記錄和應用配置都儲存在資料庫中，不應依賴容器本身儲存。

### 1. 備份資料庫

在 `deploy/core-only` 目錄執行：

```bash
mkdir -p ../../backup
docker compose exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
  > ../../backup/mindtrain.dump
```

同時把 `.env` 備份到密碼管理器或其他受控位置，不要提交到 Git。

### 2. 升級服務端

使用倉庫原始碼部署時：

```bash
cd MindTrain
git pull --ff-only
cd deploy/core-only
docker compose up -d --build
docker compose ps
```

如果自己的 Compose 使用 `ghcr.io/shigella520/mindtrain-*:latest` 映象，則執行：

```bash
docker compose pull
docker compose up -d --remove-orphans
docker compose ps
```

`latest` 指向最新正式 Release；`1.0.0` 等精確標籤內容不可變，生產部署建議優先固定精確版本。`dev` 用於測試任意功能分支，不建議長期作為正式例項的固定升級通道。Training Core 啟動時會由 Flyway 自動遷移資料庫；如果遷移失敗，應保留日誌並恢復舊版本，不要手工修改 Flyway 歷史表。

### 3. 升級 Codex Plugin

```bash
codex plugin marketplace upgrade mindtrain
codex plugin add mindtrain@mindtrain
```

更新後新建一個 Codex 任務，使新版 Skill 和 MCP bridge 重新載入。現有例項地址和 Token 儲存在 Plugin 之外，不會因升級而丟失。

MindTrain 會在任務開始及遠端工具呼叫時自動校驗 Plugin、Trainer MCP 和介面契約版本。版本不同但相容時會提醒同步升級；不相容時會停止訓練並明確指出需要升級 Plugin、服務端或兩者。看到版本提示後不要繞過校驗，升級 Plugin 後務必新建 Codex 任務。

升級完成後確認 Core、MCP 和 Web 均為 `healthy`，再開始訓練。備份恢復、版本回退和故障排查見[部署與運維](doc/部署与运维.md)，Plugin 細節見[Codex Plugin 部署](doc/CodexPlugin部署.md)。

## 常用操作

```bash
# 檢視狀態和日誌
cd deploy/core-only
docker compose ps
docker compose logs -f trainer-core trainer-mcp web

# 停止服務但保留資料
docker compose down
```

訓練題量、新題額度、積壓閾值、臨時題有效期和報表時區可在 Web 的“管理 → 訓練配置”中修改，不需要重啟服務。

## 文件導航

| 想做什麼 | 閱讀 |
| --- | --- |
| 部署到伺服器、配置反向代理、備份或升級 | [部署與運維](doc/部署与运维.md) |
| 安裝、切換分支、更新或排查 Codex Plugin | [Codex Plugin 部署](doc/CodexPlugin部署.md) |
| 本地開發、執行測試、構建映象或瞭解 CI | [開發指南](doc/开发指南.md) |
| 維護版本號或釋出正式 Release | [版本與釋出規範](doc/版本与发布规范.md) |
| 瞭解 Dashboard、Web 訓練和視覺設計 | [Web 設計](doc/Web设计.md) |
| 建立、查詢領域與知識點樹 | [知識目錄](doc/知识目录.md) |
| 檢視 REST API | [Training Core OpenAPI](contracts/openapi/trainer-core.yaml) |
| 檢視產品目標、架構與倉庫邊界 | [目標需求](doc/目标需求.md) · [概要設計](doc/概要设计.md) · [倉庫目錄規劃](doc/仓库目录规划.md) |
| 檢視 Skill 工作流 | [MindTrain Plugin Skill](plugins/mindtrain/skills/mindtrain/SKILL.md) |

## 路線圖

- 擴充領域無關 Knowledge Pack 和內容管理能力。
- 完善 Web 題庫治理、近期會話和訓練洞察。
- 接入 Anki Bridge 與 FSRS Scheduler Provider。
- 增加備份恢復、匯入匯出和多使用者能力。

歡迎透過 Issue 提交使用反饋、題目質量問題和新的知識領域建議。

## 許可證

MindTrain 使用 [MIT License](LICENSE) 開源，可自由使用、修改、分發和商用，但需保留原始版權宣告和許可證文字。

## 友情連結

<p align="center">
  <a href="https://linux.do" target="_blank">
    <img src="https://img.shields.io/badge/LINUX-DO-FFB003?style=for-the-badge&logo=linux&logoColor=white" alt="LINUX DO" />
  </a>
</p>
