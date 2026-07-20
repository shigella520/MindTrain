<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { ArrowRight, BrainCircuit, CalendarClock, RefreshCw, Sparkles } from '@lucide/vue'
import MetricCard from '../components/MetricCard.vue'
import ProgressRing from '../components/ProgressRing.vue'
import TopicHeatmap from '../components/TopicHeatmap.vue'
import { useConfigStore } from '../stores/config'
import { useDashboardStore } from '../stores/dashboard'

const config = useConfigStore()
const dashboard = useDashboardStore()
const todayCompleted = computed(() => 0)
const oldestDue = computed(() => {
  if (!dashboard.overview.oldestDueAt) return '没有逾期'
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(dashboard.overview.oldestDueAt))
})

onMounted(() => {
  if (config.configured) dashboard.refresh()
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
        <h1 class="hero-wordmark">MindTrain</h1>
        <p class="hero-description">MindTrain 根据复习积压、薄弱知识点和错误频率安排训练，让新知识的加入始终服从你的每日容量。</p>
        <div class="hero-actions">
          <RouterLink class="button primary large" to="/train">开始今日训练 <ArrowRight :size="18" /></RouterLink>
          <button class="button ghost" type="button" :disabled="dashboard.loading || !config.configured" @click="dashboard.refresh">
            <RefreshCw :size="17" :class="{ spinning: dashboard.loading }" />刷新数据
          </button>
        </div>
        <div class="hero-status-line">
          <span><CalendarClock :size="16" />最老到期：{{ oldestDue }}</span>
          <span><Sparkles :size="16" />今日新题额度：{{ dashboard.overview.newItemAllowance }}</span>
        </div>
      </div>

      <div class="hero-stage">
        <div class="orbit orbit-one"></div>
        <div class="orbit orbit-two"></div>
        <ProgressRing :value="todayCompleted * 10" :label="`${todayCompleted} / 10`" caption="今日训练" />
        <div class="floating-stat stat-due"><span>到期复习</span><strong>{{ dashboard.overview.dueCount }}</strong><em>等待完成</em></div>
        <div class="floating-stat stat-accuracy"><span>累计正确率</span><strong>{{ dashboard.accuracyPercent }}%</strong><em>{{ dashboard.overview.attempts }} 次作答</em></div>
        <div class="floating-stat stat-new"><span>新题额度</span><strong>{{ dashboard.overview.newItemAllowance }}</strong><em>{{ dashboard.overview.newItemsPaused ? '已暂停' : '可引入' }}</em></div>
      </div>
    </section>

    <p v-if="dashboard.error" class="error-banner">{{ dashboard.error }}</p>

    <section class="story-row reveal">
      <aside class="story-aside">
        <span class="story-index">01</span>
        <h2>今天学什么</h2>
        <p>先处理到期复习，再在容量允许时引入新题。积压过高时，调度器会主动停止扩张。</p>
      </aside>
      <div class="story-content">
        <div class="metric-grid four">
          <MetricCard label="到期复习" :value="dashboard.overview.dueCount" :note="dashboard.overview.newItemsPaused ? '积压中，暂停新题' : '按优先级安排'" tone="blue" />
          <MetricCard label="新题额度" :value="dashboard.overview.newItemAllowance" note="默认每轮最多 2 题" tone="peach" />
          <MetricCard label="累计作答" :value="dashboard.overview.attempts" :note="`${dashboard.overview.correct} 次正确`" tone="mint" />
          <MetricCard label="完成会话" :value="dashboard.overview.completedSessions" note="长期训练轨迹" tone="lilac" />
        </div>
        <div class="content-card scheduler-card">
          <div>
            <p class="card-kicker">SCHEDULER</p>
            <h3>{{ dashboard.overview.newItemsPaused ? '先消化积压，再学习新内容' : '复习负担处于可控范围' }}</h3>
            <p>{{ dashboard.overview.newItemsPaused ? '到期题过多或最老逾期超过阈值，系统已暂停新题。' : '当前仍可引入少量新题，默认按 8 道复习题与 2 道新题规划。' }}</p>
          </div>
          <span class="state-badge" :class="{ warning: dashboard.overview.newItemsPaused }">{{ dashboard.overview.newItemsPaused ? 'Backlog' : 'Healthy' }}</span>
        </div>
      </div>
    </section>

    <section class="story-row reveal">
      <aside class="story-aside">
        <span class="story-index">02</span>
        <h2>知识掌握</h2>
        <p>错误次数和掌握度共同描出薄弱区域。颜色越暖，越值得在下一轮优先复习。</p>
      </aside>
      <div class="story-content two-column">
        <section class="content-card">
          <div class="card-head">
            <div><p class="card-kicker">MASTERY MAP</p><h3>薄弱知识热力图</h3></div>
            <BrainCircuit :size="22" />
          </div>
          <TopicHeatmap :topics="dashboard.overview.weakTopics" />
        </section>
        <section class="content-card accuracy-card">
          <div><p class="card-kicker">ACCURACY</p><h3>累计答题质量</h3></div>
          <ProgressRing :value="dashboard.accuracyPercent" label="全部记录" caption="精确判分" />
          <p>单选与多选均按选项集合完全相等判分，避免模糊掌握度掩盖知识缺口。</p>
        </section>
      </div>
    </section>

    <section class="story-row reveal">
      <aside class="story-aside">
        <span class="story-index">03</span>
        <h2>内容资产</h2>
        <p>正式题与候选题始终分离。AI 生成内容只有经过治理流程后，才能成为长期共享资产。</p>
      </aside>
      <div class="story-content">
        <div class="metric-grid three">
          <MetricCard label="正式题" :value="dashboard.overview.publishedQuestions" note="可跨会话调度" tone="blue" />
          <MetricCard label="候选题" :value="dashboard.overview.candidateQuestions" note="等待审核或会话隔离" tone="peach" />
          <MetricCard label="调度方式" :value="dashboard.schedulerName" note="Anki / FSRS 调度规划中" tone="mint" />
        </div>
        <RouterLink class="content-card management-link" to="/admin">
          <div><p class="card-kicker">CONTENT GOVERNANCE</p><h3>进入内容与实例管理</h3><p>查看题库概况、薄弱主题和服务边界。</p></div>
          <ArrowRight :size="24" />
        </RouterLink>
      </div>
    </section>
  </main>
</template>
