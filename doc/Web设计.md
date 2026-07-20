# MindTrain Web 设计

## 技术路线

后续 Web 使用 Vue 3、TypeScript、Vite、Vue Router 和 Pinia，只访问 Training Core REST API。第一阶段 Codex MVP 不交付 Web 运行包。

基础设计令牌位于 `apps/web/src/styles/tokens.css`，其颜色、字体、圆角、玻璃表面和阴影语言基于 MIT 许可的 LinkPeek 视觉风格重新组织，不直接依赖或整份复制 LinkPeek 页面 CSS。

## 混合 Dashboard

Dashboard 采用学习者优先、管理信息下沉的混合布局：

1. 顶部学习 Hero：今日 10 题、完成度、到期积压、新题额度、连续训练和开始训练入口。
2. 学习洞察：准确率趋势、知识点热力图、薄弱主题和近期会话。
3. 内容治理：正式题、候选题、来源缺失、知识覆盖率。
4. 实例状态：Core、数据库、MCP 和调度器健康状态。

Dashboard 使用 LinkPeek 风格的暖灰渐变背景、玻璃卡片、胶囊筛选和叙事式分区；管理页使用 296px 侧边栏、白色面板、表格和表单。

## 响应式与验证

- 桌面：大于 1180px，Hero 双栏，管理侧边栏固定。
- 平板：781–1180px，Dashboard 分区改单列，侧边栏可收起。
- 手机：不大于 780px，卡片、筛选和表格转为纵向布局。
- 紧凑手机：不大于 520px，减少留白和装饰光晕。

正式实现时为 Dashboard 和管理页分别建立桌面、平板和手机视觉回归截图。
