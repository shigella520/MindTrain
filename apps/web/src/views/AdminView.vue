<script setup lang="ts">
import { onMounted } from 'vue'
import { BookCopy, CircleGauge, Database, FileQuestion, ServerCog, Tags } from '@lucide/vue'
import { useConfigStore } from '../stores/config'
import { useDashboardStore } from '../stores/dashboard'

const config = useConfigStore()
const dashboard = useDashboardStore()
onMounted(() => { dashboard.refresh() })
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
          <article><FileQuestion :size="22" /><span>可复习题</span><strong>{{ dashboard.overview.activeQuestions }}</strong></article>
          <article><BookCopy :size="22" /><span>待答 AI 题</span><strong>{{ dashboard.overview.pendingGeneratedQuestions }}</strong></article>
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
              <tr><td>可复习题</td><td>{{ dashboard.overview.activeQuestions }}</td><td>可跨会话调度</td><td><span class="table-status success">普通题</span></td></tr>
              <tr><td>待答 AI 题</td><td>{{ dashboard.overview.pendingGeneratedQuestions }}</td><td>拒绝时物理删除，回答后转普通题</td><td><span class="table-status warning">临时</span></td></tr>
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
          <article><span class="service-icon"><ServerCog :size="20" /></span><div><strong>Training Core</strong><em>{{ config.apiBaseUrl }}</em></div><span class="table-status" :class="dashboard.error ? 'danger' : 'success'">{{ dashboard.error ? '不可用' : '已连接' }}</span></article>
          <article><span class="service-icon"><Database :size="20" /></span><div><strong>PostgreSQL</strong><em>当前页面不单独探测数据库</em></div><span class="table-status">由 Core 管理</span></article>
          <article><span class="service-icon"><CircleGauge :size="20" /></span><div><strong>{{ dashboard.schedulerName }}</strong><em>到期 {{ dashboard.overview.dueCount }} · 新题额度 {{ dashboard.overview.newItemAllowance }}</em></div><span class="table-status" :class="dashboard.error ? 'danger' : 'success'">{{ dashboard.error ? '不可用' : '运行中' }}</span></article>
        </div>
      </section>
    </div>
  </main>
</template>
