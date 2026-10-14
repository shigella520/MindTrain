<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { BookOpen, CheckCircle2, EyeOff, FileQuestion, Search, XCircle } from '@lucide/vue'
import { useRoute } from 'vue-router'
import { coreApi, CoreApiError } from '../services/core'
import { useConfigStore } from '../stores/config'
import type { KnowledgeDomain, QuestionDetail, QuestionSummary } from '../types/api'

const config = useConfigStore()
const route = useRoute()
const domains = ref<KnowledgeDomain[]>([])
const items = ref<QuestionSummary[]>([])
const selected = ref<QuestionDetail | null>(null)
const loading = ref(false)
const error = ref('')
const query = ref('')
const domainId = ref('')
const topicId = ref(typeof route.query.topicId === 'string' ? route.query.topicId : '')
const type = ref('')
const learningState = ref(typeof route.query.learningState === 'string' ? route.query.learningState : 'learned')
const result = ref('')
const questionStatus = ref('active')
const maintaining = ref(false)
const confirmingDelete = ref(false)
const deletionReason = ref('不再需要训练这道题')
const notice = ref('')
let timer: number | undefined

const listTitle = computed(() => learningState.value === 'all' ? '全部题目' : learningState.value === 'unseen' ? '未学习题目' : '已学习题目')
const emptyCopy = computed(() => `没有符合条件的${listTitle.value}`)

async function load() {
  if (!config.configured) return
  loading.value = true
  error.value = ''
  try {
    const filters: Record<string, string> = { learningState: learningState.value, limit: '100', status: questionStatus.value }
    if (query.value.trim()) filters.q = query.value.trim()
    if (domainId.value) filters.domainId = domainId.value
    if (topicId.value.trim()) filters.topicId = topicId.value.trim()
    if (type.value) filters.type = type.value
    if (result.value) filters.result = result.value
    const page = await coreApi.questions(filters)
    items.value = page.items
    if (selected.value && !items.value.some(item => item.id === selected.value?.summary.id)) selected.value = null
  } catch (cause) {
    error.value = cause instanceof CoreApiError ? cause.message : '题库读取失败'
  } finally {
    loading.value = false
  }
}

async function openQuestion(id: string) {
  confirmingDelete.value = false
  error.value = ''
  try { selected.value = await coreApi.question(id) }
  catch (cause) { error.value = cause instanceof CoreApiError ? cause.message : '题目详情读取失败' }
}

async function maintainQuestion(remove: boolean) {
  if (!selected.value || maintaining.value || (remove && !deletionReason.value.trim())) return
  const { id, version } = selected.value.summary
  maintaining.value = true
  error.value = ''
  notice.value = ''
  try {
    if (remove) await coreApi.deleteQuestion(id, version, deletionReason.value.trim())
    else await coreApi.restoreQuestion(id, version)
    confirmingDelete.value = false
    await load()
    notice.value = remove ? '题目已移出题库和训练，历史答题记录保留。' : '题目已恢复，将按原复习进度参与训练。'
  } catch (cause) {
    error.value = cause instanceof CoreApiError ? cause.message : '题目操作失败'
    if (cause instanceof CoreApiError && cause.code === 'question_version_conflict') {
      selected.value = await coreApi.question(id).catch(() => selected.value)
      confirmingDelete.value = false
      error.value = '题目版本已更新，请查看最新内容后重新操作。'
    }
  } finally { maintaining.value = false }
}

function formatDate(value: string | null) {
  return value ? new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '—'
}

watch([domainId, topicId, type, learningState, result, questionStatus], () => {
  confirmingDelete.value = false
  void load()
})
watch(query, () => { window.clearTimeout(timer); timer = window.setTimeout(load, 180) })
onMounted(async () => {
  if (!config.configured) return
  domains.value = await coreApi.knowledgeDomains().catch(() => [])
  await load()
})
</script>

<template>
  <main class="question-bank-page page-container">
    <header class="catalog-heading">
      <div><p class="eyebrow">QUESTION BANK</p><h1>题库</h1><p>浏览、删除或恢复题目；内容修改通过 Codex 完成，未学习题目不会提前显示答案。</p></div>
    </header>
    <section v-if="!config.configured" class="catalog-empty content-card">
      <FileQuestion :size="42" /><h2>先连接你的私人实例</h2><p>题库始终需要 Bootstrap Token，即使实例开放了匿名统计看板。</p>
      <RouterLink class="button primary" to="/settings">配置实例</RouterLink>
    </section>
    <template v-else>
      <section class="question-filters content-card">
        <select v-model="questionStatus" aria-label="题目状态"><option value="active">正常</option><option value="deleted">已删除</option></select>
        <label class="catalog-search"><Search :size="16" /><input v-model="query" placeholder="搜索题目标题或题干" /></label>
        <select v-model="domainId"><option value="">全部领域</option><option v-for="domain in domains" :key="domain.id" :value="domain.id">{{ domain.name }}</option></select>
        <input v-model="topicId" placeholder="知识点 ID" />
        <select v-model="type"><option value="">全部题型</option><option value="single_choice">单选题</option><option value="multiple_choice">多选题</option></select>
        <select v-model="learningState" aria-label="学习状态"><option value="all">全部学习状态</option><option value="learned">已学习</option><option value="unseen">未学习</option></select>
        <select v-model="result" :disabled="learningState === 'unseen'"><option value="">最近结果不限</option><option value="correct">最近答对</option><option value="wrong">最近答错</option></select>
      </section>
      <p v-if="error" class="error-banner">{{ error }}</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <section class="question-bank-workspace">
        <div class="question-list content-card">
          <div class="catalog-panel-title"><FileQuestion :size="18" /><strong>{{ listTitle }}</strong><span class="catalog-count">{{ items.length }}</span></div>
          <p v-if="loading" class="catalog-no-result">正在读取题库…</p>
          <p v-else-if="!items.length" class="catalog-no-result">{{ emptyCopy }}</p>
          <button v-for="item in items" v-else :key="item.id" type="button" class="question-row" :class="{ active: selected?.summary.id === item.id }" @click="openQuestion(item.id)">
            <span><strong>{{ item.title }}</strong><em>{{ item.stem }}</em></span>
            <small>{{ item.type === 'multiple_choice' ? '多选' : '单选' }} · 难度 {{ item.difficulty }} · {{ item.attemptCount }} 次作答</small>
          </button>
        </div>
        <aside class="question-detail content-card">
          <template v-if="selected">
            <div class="topic-section">
              <p v-if="selected.summary.status === 'deleted'">已删除 · {{ formatDate(selected.summary.deletedAt) }} · {{ selected.summary.deletionReason }}</p>
              <button v-if="selected.summary.status === 'deleted'" class="button secondary" :disabled="maintaining" @click="maintainQuestion(false)">恢复题目</button>
              <button v-else class="button secondary" :disabled="maintaining" @click="confirmingDelete = true">删除题目</button>
              <div v-if="confirmingDelete" class="question-delete-confirm" role="group" aria-label="确认删除题目">
                <p>删除后退出训练，保留历史答题记录。正在等待回答的该题会被取消，可以随时恢复题目。</p>
                <label>删除原因<input v-model="deletionReason" :disabled="maintaining" /></label>
                <div><button class="button secondary" :disabled="maintaining" @click="confirmingDelete = false">取消</button><button class="button primary" :disabled="maintaining || !deletionReason.trim()" @click="maintainQuestion(true)">确认删除</button></div>
              </div>
            </div>
            <p class="card-kicker">QUESTION DETAIL · V{{ selected.summary.version }}</p><h2>{{ selected.summary.title }}</h2><p>{{ selected.summary.stem }}</p>
            <ol class="question-detail-options"><li v-for="option in selected.question.options" :key="option.id"><strong>{{ option.id }}</strong>{{ option.text }}</li></ol>
            <div v-if="selected.answerVisible" class="answer-disclosure"><CheckCircle2 :size="18" /><strong>当前版本答案：{{ selected.question.correctOptionIds?.join('、') }}</strong><p>{{ selected.question.explanation?.conclusion }}</p></div>
            <div v-else class="answer-hidden"><EyeOff :size="18" /><span>当前版本尚未作答，答案与解析已隐藏。</span></div>
            <div class="topic-section"><h3>知识点</h3><div class="topic-tags"><span v-for="id in selected.summary.topicIds" :key="id">{{ id }}</span></div></div>
            <div class="topic-section"><h3>最近作答</h3><p v-if="!selected.attempts.length">尚无作答记录。</p><div v-else class="attempt-history"><details v-for="attempt in selected.attempts" :key="attempt.id"><summary><CheckCircle2 v-if="attempt.correct" :size="15" /><XCircle v-else :size="15" />V{{ attempt.questionVersion }} · {{ attempt.selectedOptionIds.join('、') }} · {{ formatDate(attempt.answeredAt) }}</summary><p>{{ attempt.question.stem }}</p><ol class="question-detail-options"><li v-for="option in attempt.question.options" :key="option.id"><strong>{{ option.id }}</strong>{{ option.text }}</li></ol><p>当时答案：{{ attempt.question.correctOptionIds?.join('、') }}</p><p>{{ attempt.question.explanation?.conclusion }}</p></details></div></div>
            <div class="topic-section"><h3>来源</h3><p v-if="!selected.summary.sources.length">暂无来源。</p><a v-for="(source, index) in selected.summary.sources" v-else :key="index" :href="source.url" target="_blank" rel="noreferrer">{{ source.title || source.url }}</a></div>
          </template>
          <div v-else class="catalog-detail-empty"><BookOpen :size="34" /><span>选择一道题查看详情</span></div>
        </aside>
      </section>
    </template>
  </main>
</template>
