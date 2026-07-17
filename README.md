# java-interview-coach

AI 驱动的 Java 面试训练与题库资产平台。

项目第一阶段通过 Codex Skill 实现 Java 8–21 后端面试单选/多选训练、加权随机选题、精确判题、结构化详解、深入追问和学习资产沉淀，再演进为独立的 Spring Boot Web App。

当前阶段为概要设计，详见 [概要设计](doc/概要设计.md)。

## 仓库分层

- `skill/`：Codex 面试教练工作流与辅助脚本。
- `schemas/`：Codex 与未来 Web App 共用的数据协议。
- `assets/`：正式题库、候选题、知识点、来源和知识图谱。
- `learning-data/`：答题历史、掌握度、错题和训练会话。
- `prompts/`：可版本化、可迁移的 AI 提示词。
- `evals/`：用于检查生成和评分质量的评测集。

## 第一阶段使用

在 Codex 中使用 `$java-interview-coach`，或直接说“开始 Java 面试练习”。默认进行 10 道主问题，每题后可选择继续、深入追问或结束总结。

本地脚本环境与完整检查：

```bash
make check
```

创建训练场次并选择第一题：

```bash
.skill-venv/bin/python skill/java-interview-coach/scripts/new-session.py
.skill-venv/bin/python skill/java-interview-coach/scripts/select-question.py
```
