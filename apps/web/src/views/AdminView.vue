<script setup lang="ts">
import { onMounted } from 'vue'
import { BookCopy, CircleGauge, Database, FileQuestion, ServerCog, Tags } from '@lucide/vue'
import { useConfigStore } from '../stores/config'
import { useDashboardStore } from '../stores/dashboard'

const config = useConfigStore()
const dashboard = useDashboardStore()
onMounted(() => { if (config.configured) dashboard.refresh() })
</script>

<template>
  <main class="admin-page page-container">
    <aside class="admin-sidebar">
      <div><p class="eyebrow">MINDTRAIN ADMIN</p><h2>管理模块</h2></div>
      <nav>
        <a class="active" href="#overview"><CircleGauge :size="18" />概览</a>
        <a href="#content"><BookCopy :size="18" />知识内容</a>
        <a href="#topics"><Tags :size="18" />知识点</a>
        <a href="#service"><ServerCog :size="18" />实例状态</a>
      </nav>
      <div class="sidebar-note"><Database :size="18" /><span>Core 是唯一权威数据源</span></div>
    </aside>

    <div class="admin-workspace">
      <section id="overview" class="admin-panel">
        <div class="panel-head"><div><p class="card-kicker">OVERVIEW</p><h1>内容与训练概览</h1></div><span class="state-badge">Single user</span></div>
        <div class="admin-stat-grid">
          <article><FileQuestion :size="22" /><span>正式题</span><strong>{{ dashboard.overview.publishedQuestions }}</strong></article>
          <article><BookCopy :size="22" /><span>候选题</span><strong>{{ dashboard.overview.candidateQuestions }}</strong></article>
          <article><CircleGauge :size="22" /><span>累计作答</span><strong>{{ dashboard.overview.attempts }}</strong></article>
          <article><Database :size="22" /><span>完成会话</span><strong>{{ dashboard.overview.completedSessions }}</strong></article>
        </div>
      </section>

      <section id="content" class="admin-panel">
        <div class="panel-head"><div><p class="card-kicker">CONTENT</p><h2>内容治理</h2></div><span class="coming-soon">查询 API 待补充</span></div>
        <div class="table-shell">
          <table>
            <thead><tr><th>资产类型</th><th>数量</th><th>当前边界</th><th>状态</th></tr></thead>
            <tbody>
              <tr><td>正式题</td><td>{{ dashboard.overview.publishedQuestions }}</td><td>可跨会话调度</td><td><span class="table-status success">Published</span></td></tr>
              <tr><td>候选题</td><td>{{ dashboard.overview.candidateQuestions }}</td><td>会话隔离，不自动发布</td><td><span class="table-status warning">Candidate</span></td></tr>
              <tr><td>来源缺失</td><td>—</td><td>等待内容查询 API</td><td><span class="table-status">Planned</span></td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <section id="topics" class="admin-panel">
        <div class="panel-head"><div><p class="card-kicker">WEAK TOPICS</p><h2>优先治理知识点</h2></div></div>
        <div v-if="dashboard.overview.weakTopics.length" class="weak-topic-list">
          <article v-for="topic in dashboard.overview.weakTopics" :key="topic.topic_id">
            <div><strong>{{ topic.name || topic.topic_id }}</strong><span>{{ topic.correct_count }} 正确 · {{ topic.wrong_count }} 错误</span></div>
            <div class="mastery-bar"><i :style="{ width: `${topic.mastery_score}%` }"></i></div>
            <em>{{ topic.mastery_score }}</em>
          </article>
        </div>
        <div v-else class="empty-panel"><strong>暂无薄弱知识点</strong><span>完成训练后自动形成聚合掌握度。</span></div>
      </section>

      <section id="service" class="admin-panel">
        <div class="panel-head"><div><p class="card-kicker">INSTANCE</p><h2>服务状态</h2></div></div>
        <div class="service-list">
          <article><span class="service-icon"><ServerCog :size="20" /></span><div><strong>Training Core</strong><em>{{ config.apiBaseUrl }}</em></div><span class="table-status" :class="dashboard.error ? 'danger' : 'success'">{{ dashboard.error ? 'Unavailable' : 'Connected' }}</span></article>
          <article><span class="service-icon"><Database :size="20" /></span><div><strong>PostgreSQL</strong><em>由 Core 健康状态间接确认</em></div><span class="table-status">Managed</span></article>
          <article><span class="service-icon"><CircleGauge :size="20" /></span><div><strong>{{ dashboard.schedulerName }}</strong><em>到期 {{ dashboard.overview.dueCount }} · 新题额度 {{ dashboard.overview.newItemAllowance }}</em></div><span class="table-status success">运行中</span></article>
        </div>
      </section>
    </div>
  </main>
</template>
