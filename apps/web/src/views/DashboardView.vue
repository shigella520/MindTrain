<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, BrainCircuit, CalendarClock, Check, Copy, RefreshCw, Sparkles } from '@lucide/vue'
import MetricCard from '../components/MetricCard.vue'
import MasteryHighlights from '../components/MasteryHighlights.vue'
import ProgressRing from '../components/ProgressRing.vue'
import { dailyProgressPercent, schedulerCopy } from '../domain/dashboard'
import { useConfigStore } from '../stores/config'
import { useDashboardStore } from '../stores/dashboard'

const config = useConfigStore()
const dashboard = useDashboardStore()
const copied = ref(false)
const initializationPrompt = '使用 $mindtrain，根据【我的学习目标】创建一个训练领域。请先展示完整知识点树，等我确认后再保存。'
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

async function copyPrompt() {
  await navigator.clipboard.writeText(initializationPrompt)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1600)
}

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
    <section v-else-if="dashboard.overview.knowledgeDomainCount === 0" class="setup-banner reveal">
      <div><span class="eyebrow">FIRST DOMAIN</span><h2>把想学的知识，变成每天可以练的题</h2><p>复制一句提示给 Codex，确认知识点树后再保存，不需要手工配置题库。</p></div>
      <button class="button primary" type="button" @click="copyPrompt"><Check v-if="copied" :size="17" /><Copy v-else :size="17" />{{ copied ? '已复制' : '复制创建提示' }}</button>
    </section>
    <section v-else-if="dashboard.overview.attempts === 0" class="setup-banner reveal">
      <div><span class="eyebrow">FIRST TRAINING</span><h2>训练领域已就绪</h2><p>开始第一次训练；AI 负责出题和讲解，MindTrain 负责判分、记录和调度。</p></div>
      <RouterLink class="button primary" to="/train">开始第一次训练 <ArrowRight :size="17" /></RouterLink>
    </section>

    <section class="hero reveal">
      <div class="hero-copy">
        <p class="eyebrow">PRIVATE AI KNOWLEDGE TRAINING</p>
        <h1 class="hero-wordmark"><span>MindTrain</span><span>Dashboard</span></h1>
        <p class="hero-description">私有 AI 知识训练平台。AI 负责出题和讲解，MindTrain 负责判分、记录和调度。</p>
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
          <RouterLink class="button ghost" to="/catalog">查看训练地图 <ArrowRight :size="16" /></RouterLink>
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
        <RouterLink class="content-card management-link" to="/questions">
          <div><p class="card-kicker">QUESTION BANK</p><h3>浏览只读题库</h3><p>{{ dashboard.overview.knowledgeDomainCount }} 个领域 · {{ dashboard.overview.knowledgeTopicCount }} 个知识点 · {{ dashboard.overview.activeQuestions }} 道生效题目</p></div>
          <ArrowRight :size="24" />
        </RouterLink>
      </div>
    </section>
  </main>
</template>
