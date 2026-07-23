<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { ArrowRight, BrainCircuit, CalendarClock, RefreshCw, Sparkles } from '@lucide/vue'
import MetricCard from '../components/MetricCard.vue'
import MasteryHighlights from '../components/MasteryHighlights.vue'
import ProgressRing from '../components/ProgressRing.vue'
import { dailyProgressPercent, schedulerCopy } from '../domain/dashboard'
import { useConfigStore } from '../stores/config'
import { useDashboardStore } from '../stores/dashboard'

const config = useConfigStore()
const dashboard = useDashboardStore()
const todayCompleted = computed(() => dashboard.overview.todayCompletedMainQuestions)
const dailyTarget = computed(() => dashboard.overview.dailyTarget)
const todayProgress = computed(() => dailyProgressPercent(todayCompleted.value, dailyTarget.value))
const oldestDue = computed(() => {
  if (!dashboard.overview.oldestDueAt) return '没有逾期'
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(dashboard.overview.oldestDueAt))
})
const todayAccuracyPercent = computed(() => Math.round(dashboard.overview.todayAccuracy * 100))
const trainingActionLabel = computed(() => {
  if (todayCompleted.value >= dailyTarget.value) return '继续自主训练'
  return dashboard.overview.dueCount > 0 ? '开始今日复习' : '开始今日训练'
})
const schedulerState = computed(() => schedulerCopy({
  status: dashboard.overview.schedulerStatus,
  dueCount: dashboard.overview.dueCount,
  oldestDueLabel: oldestDue.value,
  todayCompleted: todayCompleted.value,
  dailyTarget: dailyTarget.value,
  todayNewItemsIntroduced: dashboard.overview.todayNewItemsIntroduced,
  pauseReason: dashboard.overview.newItemsPauseReason,
}))
const masterySummary = computed(() => `当前有 ${dashboard.overview.weakTopics.length} 个待加强、${dashboard.overview.strongTopics.length} 个擅长知识点，另有 ${dashboard.overview.insufficientEvidenceTopicCount} 个正在积累样本。`)
const accuracySummary = computed(() => `累计完成 ${dashboard.overview.attempts} 次作答，其中 ${dashboard.overview.correct} 次正确、${Math.max(0, dashboard.overview.attempts - dashboard.overview.correct)} 次错误。`)
const contentSummary = computed(() => `当前有 ${dashboard.overview.activeQuestions} 道生效题目：${dashboard.overview.reviewableQuestionCount} 道已学习可复习，${dashboard.overview.unseenQuestionCount} 道尚未学习。`)

onMounted(() => {
  dashboard.refresh()
})
</script>

<template>
  <main class="dashboard-page page-container">
    <section v-if="!config.configured" class="setup-banner reveal">
      <div>
        <span class="eyebrow">FIRST RUN</span>
        <h2>先连接你的私有 MindTrain 实例</h2>
        <p>Web 只在浏览器本地保存地址与单用户 Token，不会写入仓库。</p>
      </div>
      <RouterLink class="button primary" to="/settings">配置实例 <ArrowRight :size="17" /></RouterLink>
    </section>

    <section class="hero reveal">
      <div class="hero-copy">
        <p class="eyebrow">PERSONAL LEARNING SYSTEM</p>
        <h1 class="hero-wordmark"><span>MindTrain</span><span>Dashboard</span></h1>
        <p class="hero-description">MindTrain 根据复习积压、薄弱知识点和错误频率安排训练，让新知识的加入始终服从你的每日容量。</p>
        <div class="hero-actions">
          <RouterLink class="button primary large" to="/train">{{ trainingActionLabel }} <ArrowRight :size="18" /></RouterLink>
          <button class="button ghost" type="button" :disabled="dashboard.loading" @click="dashboard.refresh">
            <RefreshCw :size="17" :class="{ spinning: dashboard.loading }" />刷新数据
          </button>
        </div>
        <div class="hero-status-line">
          <span><CalendarClock :size="16" />最老到期：{{ oldestDue }}</span>
          <span><Sparkles :size="16" />今日新题：{{ dashboard.overview.todayNewItemsIntroduced }}</span>
        </div>
      </div>

      <div class="hero-stage">
        <div class="orbit orbit-one"></div>
        <div class="orbit orbit-two"></div>
        <ProgressRing :value="todayProgress" :label="`${todayCompleted} / ${dailyTarget}`" caption="今日训练" />
        <div class="floating-stat stat-due"><span>到期复习</span><strong>{{ dashboard.overview.dueCount }}</strong><em>等待完成</em></div>
        <div class="floating-stat stat-accuracy"><span>累计正确率</span><strong>{{ dashboard.accuracyPercent }}%</strong><em>{{ dashboard.overview.attempts }} 次作答</em></div>
        <div class="floating-stat stat-new"><span>今日新题</span><strong>{{ dashboard.overview.todayNewItemsIntroduced }}</strong><em>{{ dashboard.overview.newItemsPaused ? '引入已暂停' : `每轮上限 ${dashboard.overview.newBudget}` }}</em></div>
      </div>
    </section>

    <p v-if="dashboard.error" class="error-banner">{{ dashboard.error }}</p>

    <section class="story-row reveal">
      <aside class="story-aside">
        <span class="story-index">01</span>
        <h2>今天学什么</h2>
        <p>今日已完成 {{ todayCompleted }} / {{ dailyTarget }} 道主问题，复习 {{ dashboard.overview.todayReviewCompleted }} 道，新引入 {{ dashboard.overview.todayNewItemsIntroduced }} 道。</p>
      </aside>
      <div class="story-content">
        <div class="metric-grid four">
          <MetricCard label="到期复习" :value="dashboard.overview.dueCount" :note="oldestDue" tone="blue" />
          <MetricCard label="今日已复习" :value="dashboard.overview.todayReviewCompleted" :note="`当前仍有 ${dashboard.overview.dueCount} 道到期`" tone="peach" />
          <MetricCard label="今日新题" :value="dashboard.overview.todayNewItemsIntroduced" :note="dashboard.overview.newItemsPaused ? '当前已暂停引入' : `每轮计划上限 ${dashboard.overview.newBudget} 道`" tone="mint" />
          <MetricCard label="今日进度" :value="`${todayCompleted} / ${dailyTarget}`" :note="`${todayAccuracyPercent}% 正确 · ${dashboard.overview.todayCompletedSessions} 个完成会话`" tone="lilac" />
        </div>
        <div class="content-card scheduler-card">
          <div>
            <div class="scheduler-card-head"><p class="card-kicker">SCHEDULER · {{ dashboard.schedulerName }}</p><span class="state-badge" :class="`scheduler-${schedulerState.tone}`">{{ schedulerState.badge }}</span></div>
            <h3>{{ schedulerState.title }}</h3>
            <p>{{ schedulerState.description }}</p>
          </div>
        </div>
      </div>
    </section>

    <section class="story-row reveal">
      <aside class="story-aside">
        <span class="story-index">02</span>
        <h2>知识掌握</h2>
        <p>{{ masterySummary }}</p>
      </aside>
      <div class="story-content two-column">
        <section class="content-card">
          <div class="card-head">
            <div><p class="card-kicker">MASTERY BOARD</p><h3>知识掌握双榜</h3></div>
            <BrainCircuit :size="22" />
          </div>
          <MasteryHighlights
            :weak-topics="dashboard.overview.weakTopics"
            :strong-topics="dashboard.overview.strongTopics"
            :insufficient-evidence-topic-count="dashboard.overview.insufficientEvidenceTopicCount"
          />
        </section>
        <section class="content-card accuracy-card">
          <div><p class="card-kicker">ACCURACY</p><h3>累计答题质量</h3></div>
          <ProgressRing :value="dashboard.accuracyPercent" label="全部记录" caption="精确判分" />
          <p>{{ accuracySummary }}</p>
        </section>
      </div>
    </section>

    <section class="story-row reveal">
      <aside class="story-aside">
        <span class="story-index">03</span>
        <h2>内容资产</h2>
        <p>{{ contentSummary }}</p>
      </aside>
      <div class="story-content">
        <div class="metric-grid three">
          <MetricCard label="已学习可复习" :value="dashboard.overview.reviewableQuestionCount" :note="`${dashboard.overview.dueCount} 道当前到期`" tone="blue" />
          <MetricCard label="尚未学习" :value="dashboard.overview.unseenQuestionCount" :note="`${dashboard.overview.activeQuestions} 道生效题目中的新内容`" tone="peach" />
          <MetricCard label="待答 AI 题" :value="dashboard.overview.pendingGeneratedQuestions" :note="dashboard.overview.pendingGeneratedQuestions ? '回答后进入普通调度' : '当前没有临时题积压'" tone="mint" />
        </div>
        <RouterLink class="content-card management-link" to="/admin">
          <div><p class="card-kicker">CONTENT GOVERNANCE</p><h3>进入内容与实例管理</h3><p>{{ dashboard.overview.knowledgeDomainCount }} 个领域 · {{ dashboard.overview.knowledgeTopicCount }} 个知识点 · {{ dashboard.schedulerName }}</p></div>
          <ArrowRight :size="24" />
        </RouterLink>
      </div>
    </section>
  </main>
</template>
