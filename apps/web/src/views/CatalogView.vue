<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { BookOpen, Check, ChevronsDown, ChevronsUp, Copy, FileQuestion, FolderTree, Search, Sparkles } from '@lucide/vue'
import KnowledgeTreeNode from '../components/KnowledgeTreeNode.vue'
import { coreApi, CoreApiError } from '../services/core'
import { useConfigStore } from '../stores/config'
import type { KnowledgeCatalogTree, KnowledgeDomain, KnowledgeTopicDetail } from '../types/api'

const config = useConfigStore()
const domains = ref<KnowledgeDomain[]>([])
const selectedDomainId = ref('')
const tree = ref<KnowledgeCatalogTree | null>(null)
const topic = ref<KnowledgeTopicDetail | null>(null)
const domainQuery = ref('')
const topicQuery = ref('')
const matchedIds = ref<Set<string> | null>(null)
const loading = ref(false)
const error = ref('')
const copied = ref(false)
const allTopicsExpanded = ref(false)
const initializationPrompt = '使用 $mindtrain，根据【我的学习目标】创建一个训练领域。请先展示完整知识点树，等我确认后再保存。'
let searchSequence = 0
let searchTimer: number | undefined

const visibleDomains = computed(() => {
  const query = domainQuery.value.trim().toLocaleLowerCase()
  if (!query) return domains.value
  return domains.value.filter(domain => `${domain.name} ${domain.description}`.toLocaleLowerCase().includes(query))
})

async function loadDomains() {
  if (!config.configured) return
  loading.value = true
  error.value = ''
  try {
    domains.value = await coreApi.knowledgeDomains()
    if (!selectedDomainId.value && domains.value.length) selectedDomainId.value = domains.value[0].id
  } catch (cause) {
    error.value = message(cause, '无法读取知识目录')
  } finally {
    loading.value = false
  }
}

async function loadTree(domainId: string) {
  if (!domainId) return
  allTopicsExpanded.value = false
  tree.value = null
  topic.value = null
  topicQuery.value = ''
  matchedIds.value = null
  try {
    tree.value = await coreApi.knowledgeCatalogTree(domainId)
    const first = tree.value.roots[0]
    if (first) await selectTopic(first.id)
  } catch (cause) {
    error.value = message(cause, '无法读取知识点树')
  }
}

async function selectTopic(topicId: string) {
  try {
    topic.value = await coreApi.knowledgeTopic(topicId)
  } catch (cause) {
    error.value = message(cause, '无法读取知识点详情')
  }
}

async function searchTopics(query: string) {
  const sequence = ++searchSequence
  const value = query.trim()
  if (!value) {
    matchedIds.value = null
    return
  }
  try {
    const result = await coreApi.searchKnowledgeTopics(value, selectedDomainId.value)
    if (sequence === searchSequence) matchedIds.value = new Set(result.items.map(item => item.id))
  } catch (cause) {
    if (sequence === searchSequence) error.value = message(cause, '知识点搜索失败')
  }
}

async function copyPrompt() {
  await navigator.clipboard.writeText(initializationPrompt)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1600)
}

function message(cause: unknown, fallback: string) {
  return cause instanceof CoreApiError ? cause.message : fallback
}

watch(selectedDomainId, loadTree)
watch(topicQuery, value => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => searchTopics(value), 180)
})
onMounted(loadDomains)
</script>

<template>
  <main class="catalog-page page-container">
    <header class="catalog-heading">
      <div><p class="eyebrow">KNOWLEDGE CATALOG</p><h1>知识目录</h1><p>浏览训练领域、知识点树、题目覆盖与掌握情况。创建新领域时，请将提示中的“【我的学习目标】”替换为具体内容。</p></div>
      <button class="button secondary" type="button" @click="copyPrompt"><Check v-if="copied" :size="17" /><Copy v-else :size="17" />{{ copied ? '已复制' : '复制 Codex 初始化提示' }}</button>
    </header>

    <section v-if="!config.configured" class="catalog-empty content-card">
      <FolderTree :size="42" /><h2>先连接你的私人实例</h2><p>知识目录包含私人领域、知识点和来源信息，因此始终需要 Bootstrap Token。</p>
      <RouterLink class="button primary" to="/settings">配置实例</RouterLink>
    </section>
    <p v-else-if="error" class="error-banner">{{ error }}</p>
    <section v-else-if="!loading && !domains.length" class="catalog-empty content-card">
      <Sparkles :size="42" /><h2>还没有训练领域</h2><p>复制提示后，将“【我的学习目标】”替换为具体的学习内容；也可以指定一个本地资料目录。确认知识点树后才会保存。</p>
      <button class="button primary" type="button" @click="copyPrompt"><Copy :size="17" />复制开始提示</button>
    </section>
    <section v-else class="catalog-workspace">
      <aside class="catalog-domains">
        <div class="catalog-panel-title"><BookOpen :size="18" /><strong>训练领域</strong><span class="catalog-count">{{ domains.length }}</span></div>
        <label class="catalog-search"><Search :size="16" /><input v-model="domainQuery" placeholder="搜索领域" /></label>
        <div class="domain-list">
          <button v-for="domain in visibleDomains" :key="domain.id" type="button" :class="{ active: selectedDomainId === domain.id }" @click="selectedDomainId = domain.id">
            <strong>{{ domain.name }}</strong><span>{{ domain.rootTopicCount }} 个根节点 · {{ domain.topicCount }} 个知识点</span><em>{{ domain.activeQuestionCount }} 道题</em>
          </button>
        </div>
      </aside>

      <section class="catalog-tree-panel">
        <div class="catalog-panel-title">
          <FolderTree :size="18" /><strong>{{ tree?.domain.name || '知识点树' }}</strong>
          <div class="catalog-tree-controls">
            <button type="button" :aria-label="allTopicsExpanded ? '全部收起' : '全部展开'" @click="allTopicsExpanded = !allTopicsExpanded">
              <ChevronsUp v-if="allTopicsExpanded" :size="15" /><ChevronsDown v-else :size="15" />{{ allTopicsExpanded ? '全部收起' : '全部展开' }}
            </button>
            <span class="catalog-count">{{ tree?.domain.topicCount || 0 }}</span>
          </div>
        </div>
        <label class="catalog-search"><Search :size="16" /><input v-model="topicQuery" placeholder="搜索名称、说明或关键词" /></label>
        <p v-if="matchedIds && !matchedIds.size" class="catalog-no-result">没有匹配的知识点</p>
        <ul v-else class="catalog-tree">
          <KnowledgeTreeNode v-for="root in tree?.roots || []" :key="root.id" :node="root" :selected-id="topic?.id" :matched-ids="matchedIds" :expand-all="allTopicsExpanded" @select="selectTopic" />
        </ul>
      </section>

      <aside class="catalog-detail">
        <template v-if="topic">
          <p class="card-kicker">TOPIC DETAIL</p><h2>{{ topic.name }}</h2>
          <p class="topic-path">{{ [topic.domainName, ...topic.ancestorPath, topic.name].join(' / ') }}</p>
          <p class="topic-description">{{ topic.description || '暂无知识点说明。' }}</p>
          <div class="topic-metrics"><span><FileQuestion :size="16" /><strong>{{ topic.activeQuestionCount }}</strong>道题</span><span><strong>{{ topic.masteryScore ?? '—' }}</strong>掌握度</span><span><strong>{{ topic.importance }}</strong>重要度</span></div>
          <div v-if="topic.keywords.length" class="topic-section"><h3>关键词</h3><div class="topic-tags"><span v-for="keyword in topic.keywords" :key="keyword">{{ keyword }}</span></div></div>
          <div class="topic-section"><h3>来源</h3><div v-if="topic.sources.length" class="topic-sources"><article v-for="(source, index) in topic.sources" :key="String(source.id || index)"><strong>{{ source.title || source.id || '来源' }}</strong><span>{{ source.url }}</span></article></div><p v-else>尚未绑定参考资料。</p></div>
        </template>
        <div v-else class="catalog-detail-empty"><BookOpen :size="34" /><span>选择一个知识点查看详情</span></div>
      </aside>
    </section>
  </main>
</template>
