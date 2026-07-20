# MindTrain Web 设计

## 当前实现

MindTrain Web 已使用 Vue 3、TypeScript、Vite、Vue Router 和 Pinia 实现，并且只通过 Training Core REST API 访问权威数据。

当前路由：

| 路由 | 功能 |
| --- | --- |
| `/` | 学习 Dashboard、到期积压、准确率、内容资产和薄弱知识点 |
| `/train` | 创建 10 题 Session、获取 Assignment、选择选项、精确判分和解释展示 |
| `/admin` | 正式题/候选题概览、薄弱知识点和实例状态 |
| `/settings` | 配置 Core API 地址与单用户 Bootstrap Token |

题库列表、候选审核、来源缺失和近期 Session 仍缺少 Core 查询 API。Web 明确展示对应的未完成状态，不使用伪造数据。

## 设计系统

基础设计令牌位于 `apps/web/src/styles/tokens.css`，页面样式位于 `apps/web/src/styles/base.css`。

颜色、字体、圆角、玻璃表面、柔和阴影和叙事式分区基于 MIT 许可的 LinkPeek 视觉语言重新组织，不直接依赖或整份复制 LinkPeek 页面 CSS。归属说明保存在 `apps/web/NOTICE.md` 和源码注释中。

主要令牌：

- 暖灰背景与蓝橙环境光。
- `#171717` 主文本、`#6f6b66` 次文本、`#0a84ff` 主色。
- Avenir Next、SF Pro、PingFang SC 字体栈。
- 16、22、28、38px 圆角层级。
- Dashboard 使用玻璃卡片与网格光晕；Admin 使用 296px 侧边栏和白色面板。

## Dashboard

Dashboard 采用学习者优先、管理信息下沉的混合布局：

1. 顶部学习 Hero：今日 10 题、到期积压、新题额度、准确率和开始训练入口。
2. 今日调度：复习题、新题额度、累计作答和会话数量。
3. 学习洞察：薄弱主题热力图和累计正确率。
4. 内容治理：正式题、候选题和 Scheduler Provider。
5. 管理入口：进入内容与实例概览。

Core 当前没有“今日已完成题数”接口，因此 Hero 在新页面加载时显示 `0/10`，真实 Session 进度只在训练页内维护。后续应由报告 API 增加今日训练指标。

## Web 训练

训练页直接复用 Core 的权威流程：

```text
创建 Session
    ↓
获取安全 Assignment
    ↓
单选/多选交互
    ↓
提交选项集合
    ↓
Core 精确判分
    ↓
展示正确答案与结构化解释
```

Web 不承担 AI 生成。当 Core 返回 `generation_required` 时，页面提示用户转到 Codex Knowledge Trainer 生成带来源的当前会话候选题。

## 鉴权与部署

同源 Docker 部署中，浏览器访问 Web Nginx，Nginx 将 `/api/*` 转发给容器网络中的 Training Core。Core 和 PostgreSQL 不需要暴露公网。

Bootstrap Token 保存在当前浏览器 `localStorage`，用于向 Core 发送 Bearer Token。这一实现适合单用户私有部署，但用户应只在自己的设备上使用，不应在共享浏览器保存 Token。

生产镜像由 `apps/web/Dockerfile` 构建，Compose 默认映射：

```text
127.0.0.1:4173 -> web:80
```

## 响应式与验证

- 桌面：大于 1180px，Hero 双栏，Admin 使用固定 296px 侧边栏。
- 平板：781–1180px，Dashboard 分区改单列，主导航移动到底部。
- 手机：不大于 780px，卡片、筛选和表格转为纵向布局，Admin 侧边栏变为普通区块。
- 紧凑手机：不大于 520px，按钮、指标和训练操作使用全宽布局。

实现已在桌面默认视口和 390×844 手机视口完成视觉检查，没有出现横向页面溢出。后续可把浏览器截图加入稳定的视觉回归基线。
