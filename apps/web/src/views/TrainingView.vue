<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowLeft, ArrowRight, Check, CircleAlert, Code2, LoaderCircle, RotateCcw, Sparkles, X } from '@lucide/vue'
import { coreApi, CoreApiError } from '../services/core'
import { useConfigStore } from '../stores/config'
import { initialTrainingDomainId } from '../domain/trainingDomains'
import type { Assignment, Attempt, KnowledgeDomain, Session } from '../types/api'

const config = useConfigStore()
const showCodexIntro = ref(true)
const session = ref<Session | null>(null)
const assignment = ref<Assignment | null>(null)
const result = ref<Attempt | null>(null)
const selected = ref<string[]>([])
const status = ref<'idle' | 'loading' | 'question' | 'result' | 'complete' | 'generation_required' | 'empty'>('idle')
const error = ref('')
const domains = ref<KnowledgeDomain[]>([])
const selectedDomainId = ref('')
const domainsLoading = ref(false)
const progress = computed(() => session.value ? Math.round((session.value.completedMainQuestions / session.value.targetCount) * 100) : 0)
const isMultiple = computed(() => assignment.value?.question.type === 'multiple_choice')
const canReject = computed(() => assignment.value?.sourceKind === 'candidate'
  || assignment.value?.sourceKind === 'follow_up_candidate')
const suggestedMcpUrl = `${window.location.origin}/mcp`

async function continueWithWeb() {
  showCodexIntro.value = false
  if (config.configured) await loadDomains()
}

async function loadDomains() {
  domainsLoading.value = true
  error.value = ''
  try {
    domains.value = await coreApi.knowledgeDomains()
    selectedDomainId.value = initialTrainingDomainId(domains.value)
  } catch (cause) {
    fail(cause)
  } finally {
    domainsLoading.value = false
  }
}

async function start() {
  if (!selectedDomainId.value) return
  error.value = ''
  status.value = 'loading'
  try {
    session.value = await coreApi.createSession(selectedDomainId.value)
    await next()
  } catch (cause) {
    fail(cause)
  }
}

async function next() {
  if (!session.value) return
  error.value = ''
  status.value = 'loading'
  selected.value = []
  result.value = null
  try {
    const response = await coreApi.nextAssignment(session.value.id)
    if (response.status === 'assignment' && response.assignment) {
      assignment.value = response.assignment
      status.value = 'question'
    } else if (response.status === 'session_complete') {
      status.value = 'complete'
    } else if (response.status === 'generation_required') {
      status.value = 'generation_required'
    } else {
      status.value = 'empty'
    }
  } catch (cause) {
    fail(cause)
  }
}

function toggleOption(optionId: string) {
  if (status.value !== 'question') return
  if (!isMultiple.value) {
    selected.value = [optionId]
    return
  }
  selected.value = selected.value.includes(optionId)
    ? selected.value.filter((id) => id !== optionId)
    : [...selected.value, optionId]
}

async function submit() {
  if (!assignment.value || !selected.value.length) return
  status.value = 'loading'
  error.value = ''
  try {
    result.value = await coreApi.submitAnswer(assignment.value.assignmentId, [...selected.value].sort().join(','))
    if (session.value) session.value.completedMainQuestions += assignment.value.attemptType === 'main' ? 1 : 0
    status.value = 'result'
  } catch (cause) {
    fail(cause, 'question')
  }
}

async function rejectGeneratedQuestion() {
  if (!assignment.value || !canReject.value) return
  status.value = 'loading'
  error.value = ''
  try {
    await coreApi.rejectGeneratedQuestion(assignment.value.assignmentId)
    assignment.value = null
    selected.value = []
    await next()
  } catch (cause) {
    fail(cause, 'question')
  }
}

async function finish() {
  if (session.value) {
    try { session.value = await coreApi.finishSession(session.value.id) } catch { /* keep local completion */ }
  }
  status.value = 'complete'
}

function fail(cause: unknown, fallback: typeof status.value = 'idle') {
  error.value = cause instanceof CoreApiError ? cause.message : cause instanceof Error ? cause.message : '请求失败'
  status.value = fallback
}
</script>

<template>
  <main class="training-page page-container narrow-page">
    <div class="training-head reveal">
      <RouterLink class="back-link" to="/"><ArrowLeft :size="17" />返回看板</RouterLink>
      <div v-if="session" class="session-progress">
        <span>今日训练</span>
        <strong>{{ session.completedMainQuestions }} / {{ session.targetCount }}</strong>
        <div><i :style="{ width: `${progress}%` }"></i></div>
      </div>
    </div>

    <section v-if="showCodexIntro" class="training-card codex-first-card reveal">
      <div class="intro-icon"><Code2 :size="30" /></div>
      <p class="eyebrow">CODEX FIRST</p>
      <h1>推荐使用 Codex 完成完整训练</h1>
      <p class="codex-first-summary">Codex 可以在答题过程中随时回答追问，并在题库不足时为当前会话生成带来源的 AI 临时题。Web 目前只负责复习 Core 中已有的题目。</p>
      <div class="codex-setup-guide">
        <article>
          <span>1</span>
          <div><h3>添加 MindTrain Marketplace</h3><code>codex plugin marketplace add shigella520/MindTrain --ref main</code></div>
        </article>
        <article>
          <span>2</span>
          <div><h3>安装 Plugin</h3><code>codex plugin add mindtrain@mindtrain</code></div>
        </article>
        <article>
          <span>3</span>
          <div><h3>首次配置私有实例</h3><code>$mindtrain Configure my private MindTrain instance.</code><p>配置 MCP 地址 <strong>{{ suggestedMcpUrl }}</strong> 和部署时设置的 Bootstrap Token。</p></div>
        </article>
      </div>
      <div class="codex-first-actions">
        <a class="button ghost" href="https://github.com/shigella520/MindTrain#接入-codex-plugin" target="_blank" rel="noreferrer">查看完整指引</a>
        <button class="button primary large" type="button" @click="continueWithWeb">继续通过网页复习旧题 <ArrowRight :size="18" /></button>
      </div>
    </section>

    <section v-else-if="!config.configured" class="training-card centered-card reveal">
      <CircleAlert :size="34" />
      <h1>需要先配置实例</h1>
      <p>训练页通过 Training Core 完成精确判分和复习状态更新。</p>
      <RouterLink class="button primary" to="/settings">配置实例</RouterLink>
    </section>

    <section v-else-if="domainsLoading" class="training-card centered-card reveal">
      <LoaderCircle class="spinning" :size="34" />
      <h2>正在加载训练领域</h2>
    </section>

    <section v-else-if="status === 'idle' && !domains.length" class="training-card centered-card reveal">
      <CircleAlert :size="34" />
      <h1>还没有可训练的领域</h1>
      <p>请先通过 Codex 创建并确认一个训练领域，再开始训练。</p>
      <RouterLink class="button primary" to="/catalog">查看知识目录</RouterLink>
    </section>

    <section v-else-if="status === 'idle'" class="training-card training-intro reveal">
      <div class="intro-icon"><Sparkles :size="30" /></div>
      <p class="eyebrow">DAILY TRAINING</p>
      <h1>准备好开始今天的训练了吗？</h1>
      <p>默认优先安排到期复习，并在积压和实例配置允许时加入新题。</p>
      <label v-if="domains.length > 1" class="training-domain-picker">
        <span>选择本次训练领域</span>
        <select v-model="selectedDomainId">
          <option value="" disabled>请选择训练领域</option>
          <option v-for="domain in domains" :key="domain.id" :value="domain.id">{{ domain.name }}</option>
        </select>
      </label>
      <p v-else class="training-domain-current">本次训练：{{ domains[0]?.name }}</p>
      <div class="training-rules">
        <span>精确判分</span><span>动态复习 / 新题配额</span><span>错误次日复习</span>
      </div>
      <button class="button primary large" type="button" :disabled="!selectedDomainId" @click="start">开始今日训练 <ArrowRight :size="18" /></button>
    </section>

    <section v-else-if="status === 'loading'" class="training-card centered-card reveal">
      <LoaderCircle class="spinning" :size="34" />
      <h2>正在准备下一步</h2>
      <p>调度器正在计算复习优先级。</p>
    </section>

    <section v-else-if="status === 'question' && assignment" class="training-card question-card reveal">
      <div class="question-meta">
        <span>{{ assignment.sourceKind === 'review' ? '到期复习' : assignment.sourceKind === 'candidate' ? 'AI 临时题' : '新知识' }}</span>
        <span>{{ assignment.question.type === 'multiple_choice' ? '多选题' : '单选题' }}</span>
        <span>难度 {{ assignment.question.difficulty }}/5</span>
      </div>
      <p class="eyebrow">{{ assignment.question.title }}</p>
      <h1>{{ assignment.question.stem }}</h1>
      <div class="option-list">
        <button
          v-for="option in assignment.question.options"
          :key="option.id"
          type="button"
          :class="{ selected: selected.includes(option.id) }"
          @click="toggleOption(option.id)"
        >
          <span>{{ option.id }}</span><strong>{{ option.text }}</strong><i><Check :size="16" /></i>
        </button>
      </div>
      <p class="answer-prompt">请回复选项字母，可用逗号分隔。</p>
      <div class="training-actions">
        <button class="button ghost" type="button" @click="finish">结束训练</button>
        <button v-if="canReject" class="button ghost" type="button" @click="rejectGeneratedQuestion">不适合，换一题</button>
        <button class="button primary" type="button" :disabled="!selected.length" @click="submit">提交答案 <ArrowRight :size="17" /></button>
      </div>
    </section>

    <section v-else-if="status === 'result' && result && assignment" class="training-card result-card reveal" :class="{ correct: result.correct, wrong: !result.correct }">
      <div class="result-mark"><Check v-if="result.correct" :size="28" /><X v-else :size="28" /></div>
      <p class="eyebrow">{{ result.correct ? 'ANSWER CORRECT' : 'REVIEW REQUIRED' }}</p>
      <h1>{{ result.correct ? '回答正确' : '这道题需要再理解一次' }}</h1>
      <p class="result-answer">正确答案：{{ result.correctOptionIds.join('、') }}</p>
      <div class="explanation-block">
        <h3>结论</h3><p>{{ result.explanation.conclusion || '暂无结构化结论。' }}</p>
      </div>
      <div v-if="result.explanation.optionAnalysis?.length" class="option-analysis">
        <article v-for="item in result.explanation.optionAnalysis" :key="item.optionId">
          <span :class="{ correct: item.correct }">{{ item.optionId }}</span><p>{{ item.analysis }}</p>
        </article>
      </div>
      <div v-if="result.explanation.mechanism?.length" class="explanation-block">
        <h3>机制</h3><ol><li v-for="step in result.explanation.mechanism" :key="step">{{ step }}</li></ol>
      </div>
      <div class="training-actions">
        <RouterLink class="button ghost" to="/">返回看板</RouterLink>
        <button class="button primary" type="button" @click="next">下一题 <ArrowRight :size="17" /></button>
      </div>
    </section>

    <section v-else-if="status === 'generation_required'" class="training-card centered-card reveal">
      <Sparkles :size="34" />
      <h1>需要生成新的 AI 临时题</h1>
      <p>Web MVP 不直接调用 AI。请在 Codex 中使用 MindTrain Skill，为当前会话生成带来源的临时题。</p>
      <button class="button primary" type="button" @click="finish">结束并保存会话</button>
    </section>

    <section v-else-if="status === 'empty'" class="training-card centered-card reveal">
      <RotateCcw :size="34" />
      <h1>当前没有可用题目</h1>
      <p>可能是新题额度已用完，或积压策略暂停了内容引入。</p>
      <button class="button primary" type="button" @click="finish">结束训练</button>
    </section>

    <section v-else-if="status === 'complete'" class="training-card centered-card reveal">
      <div class="completion-orb"><Check :size="34" /></div>
      <p class="eyebrow">SESSION COMPLETE</p>
      <h1>今天的训练已保存</h1>
      <p>复习状态和薄弱知识点已经更新，下次调度会根据本轮表现重新排序。</p>
      <RouterLink class="button primary" to="/">查看学习看板</RouterLink>
    </section>

    <p v-if="error" class="error-banner">{{ error }}</p>
  </main>
</template>
