# 题目维护 dev 验收

开发版本基线为 1.1.0，Maven 使用 `1.1.0-SNAPSHOT`，Plugin 使用带 `+codex` 的开发构建号。契约版本仍为 1，最低 Plugin 版本保持不变；体验新工具需同时使用本分支插件。

## 更新与验收

1. 从 `codex/question-maintenance` 执行 Publish Dev Images 工作流，确认验证及三个镜像发布成功，记录对应 SHA 镜像标签。
2. 在 Dockge 更新前备份 PostgreSQL（`pg_dump` 自定义格式），备份保存到服务器备份目录，确认文件非空并用 `pg_restore --list` 验证。不得将用户数据放入 Git。
3. 通过 Dockge 更新 `mindtrain-dev`，确认 PostgreSQL、Core、MCP、Web 全部健康。使用同分支 Plugin 打开新 Codex 任务，检查版本与工具列表。
4. 使用专用测试题验证答后修订、旧版本历史展示、删除待答题后继续训练、已删除筛选及恢复。不要删除用户真实题目来做验收。
5. 用户验证后单独决定是否合并 main，不创建正式 Release。

## 数据迁移与回滚

V9 仅增加软删除元数据、Assignment 取消原因和生命周期审计表，不删除或改写历史学习数据。`deleted` 题不参与出题，`cancelled` Assignment 不可提交。

回滚优先发布修复镜像。旧应用无法完整解释新增状态，不保证直接回退旧镜像可用；需要回退数据库时必须在维护窗口从更新前备份恢复，并明确处理更新后新增数据，禁止自动覆盖用户学习记录。
