<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { BookCopy, CircleGauge, Database, FileQuestion, Save, ServerCog, SlidersHorizontal, Tags } from '@lucide/vue'
import { useConfigStore } from '../stores/config'
import { useDashboardStore } from '../stores/dashboard'
import { coreApi, CoreApiError } from '../services/core'
import { knowledgeCatalogSummary } from '../domain/dashboard'

const config = useConfigStore()
const dashboard = useDashboardStore()
const settingsForm = reactive({
  questionCount: 10,
  newBudget: 2,
  backlogPauseThreshold: 20,
  overduePauseDays: 3,
  pendingCandidateTtlHours: 24,
  reportingTimeZone: 'Asia/Shanghai',
})
const settingsState = ref<'idle' | 'loading' | 'saving'>('idle')
const settingsMessage = ref('')
const settingsError = ref(false)
const reviewBudget = computed(() => Math.max(0, settingsForm.questionCount - settingsForm.newBudget))

async function loadTrainingSettings() {
  if (!config.configured) return
  settingsState.value = 'loading'
  settingsMessage.value = ''
  try {
    const settings = await coreApi.trainingSettings()
    Object.assign(settingsForm, {
      questionCount: settings.questionCount,
      newBudget: settings.newBudget,
      backlogPauseThreshold: settings.backlogPauseThreshold,
      overduePauseDays: settings.overduePauseDays,
      pendingCandidateTtlHours: settings.pendingCandidateTtlHours,
      reportingTimeZone: settings.reportingTimeZone,
    })
    settingsError.value = false
  } catch (cause) {
    settingsError.value = true
    settingsMessage.value = cause instanceof CoreApiError ? cause.message : '无法读取训练配置'
  } finally {
    settingsState.value = 'idle'
  }
}

async function saveTrainingSettings() {
  settingsState.value = 'saving'
  settingsMessage.value = ''
  try {
    const settings = await coreApi.updateTrainingSettings({ ...settingsForm })
    Object.assign(settingsForm, {
      questionCount: settings.questionCount,
      newBudget: settings.newBudget,
      backlogPauseThreshold: settings.backlogPauseThreshold,
      overduePauseDays: settings.overduePauseDays,
      pendingCandidateTtlHours: settings.pendingCandidateTtlHours,
      reportingTimeZone: settings.reportingTimeZone,
    })
    settingsError.value = false
    settingsMessage.value = `已保存：每轮 ${settings.questionCount} 题，其中复习 ${settings.reviewBudget} 题、新题 ${settings.newBudget} 题。`
    await dashboard.refresh()
  } catch (cause) {
    settingsError.value = true
    settingsMessage.value = cause instanceof CoreApiError ? cause.message : '保存训练配置失败'
  } finally {
    settingsState.value = 'idle'
  }
}

onMounted(() => {
  dashboard.refresh()
  loadTrainingSettings()
})
</script>

<template>
  <main class="admin-page page-container">
    <aside class="admin-sidebar">
      <div><p class="eyebrow">MINDTRAIN ADMIN</p><h2>管理模块</h2></div>
      <nav>
        <a class="active" href="#overview"><CircleGauge :size="18" />概览</a>
        <a href="#training-settings"><SlidersHorizontal :size="18" />训练配置</a>
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

      <section id="training-settings" class="admin-panel">
        <div class="panel-head">
          <div><p class="card-kicker">TRAINING POLICY</p><h2>训练配置</h2></div>
          <span class="state-badge">数据库配置</span>
        </div>
        <p class="panel-description">新建 Session 统一使用这里的题量设置。复习题数量由总题数减去新题数自动计算。</p>
        <form class="admin-settings-form" @submit.prevent="saveTrainingSettings">
          <div class="settings-field-grid">
            <label>
              <span>每轮总题数</span>
              <input v-model.number="settingsForm.questionCount" type="number" min="1" max="100" required />
              <em>所有 Codex 和 Web Session 共用</em>
            </label>
            <label>
              <span>新题计划数</span>
              <input v-model.number="settingsForm.newBudget" type="number" min="0" :max="settingsForm.questionCount" required />
              <em>必须小于或等于每轮总题数</em>
            </label>
            <label>
              <span>复习题计划数</span>
              <input :value="reviewBudget" type="number" disabled />
              <em>{{ settingsForm.questionCount }} − {{ settingsForm.newBudget }} = {{ reviewBudget }}</em>
            </label>
            <label>
              <span>积压暂停阈值</span>
              <input v-model.number="settingsForm.backlogPauseThreshold" type="number" min="0" max="10000" required />
              <em>到期题超过该数量时暂停新题</em>
            </label>
            <label>
              <span>严重逾期天数</span>
              <input v-model.number="settingsForm.overduePauseDays" type="number" min="1" max="365" required />
              <em>最老到期题超过该天数时暂停新题</em>
            </label>
            <label>
              <span>AI 临时题有效期（小时）</span>
              <input v-model.number="settingsForm.pendingCandidateTtlHours" type="number" min="1" max="8760" required />
              <em>过期且未作答的临时题会被物理清理</em>
            </label>
            <label class="wide-field">
              <span>报表时区</span>
              <input v-model.trim="settingsForm.reportingTimeZone" type="text" placeholder="Asia/Shanghai" required />
              <em>使用 IANA 时区名称，决定“今日训练”的统计边界</em>
            </label>
          </div>
          <div class="admin-settings-actions">
            <span v-if="settingsMessage" :class="settingsError ? 'settings-error' : 'settings-success'">{{ settingsMessage }}</span>
            <span v-else></span>
            <button class="button primary" type="submit" :disabled="settingsState !== 'idle'">
              <Save :size="17" />{{ settingsState === 'saving' ? '保存中…' : '保存配置' }}
            </button>
          </div>
        </form>
      </section>

      <section id="content" class="admin-panel">
        <div class="panel-head"><div><p class="card-kicker">CONTENT</p><h2>内容治理</h2></div><RouterLink class="state-badge" to="/catalog">打开知识目录</RouterLink></div>
        <div class="table-shell">
          <table>
            <thead><tr><th>资产类型</th><th>数量</th><th>当前边界</th><th>状态</th></tr></thead>
            <tbody>
              <tr><td>可复习题</td><td>{{ dashboard.overview.activeQuestions }}</td><td>可跨会话调度</td><td><span class="table-status success">普通题</span></td></tr>
              <tr><td>待答 AI 题</td><td>{{ dashboard.overview.pendingGeneratedQuestions }}</td><td>拒绝时物理删除，回答后转普通题</td><td><span class="table-status warning">临时</span></td></tr>
              <tr><td>知识目录</td><td>{{ knowledgeCatalogSummary(dashboard.overview.knowledgeDomainCount, dashboard.overview.knowledgeTopicCount) }}</td><td>领域、知识点树、搜索和来源详情</td><td><span class="table-status success">可查询</span></td></tr>
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
