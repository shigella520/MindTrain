<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ChevronRight, FileQuestion, Layers3 } from '@lucide/vue'
import type { KnowledgeTopicNode } from '../types/api'

const props = defineProps<{
  node: KnowledgeTopicNode
  selectedId?: string
  matchedIds: Set<string> | null
  expandAll: boolean
}>()
const emit = defineEmits<{ select: [topicId: string] }>()
const expanded = ref(false)

function containsMatch(node: KnowledgeTopicNode): boolean {
  return props.matchedIds === null || props.matchedIds.has(node.id) || node.children.some(containsMatch)
}

const visible = computed(() => containsMatch(props.node))
const forceExpanded = computed(() => props.matchedIds !== null && props.node.children.some(containsMatch))
const effectivelyExpanded = computed(() => expanded.value || forceExpanded.value)
const statusLabel = computed(() => ({
  untrained: '未训练', accumulating: '积累中', needs_work: '待加强', strengthening: '巩固中', strong: '已掌握',
} as Record<string, string>)[props.node.training.status || ''] || '进度汇总')
watch(() => props.expandAll, value => { expanded.value = value })
</script>

<template>
  <li v-if="visible" class="catalog-tree-node">
    <div class="catalog-tree-row" :class="{ selected: selectedId === node.id }">
      <button
        class="tree-toggle"
        type="button"
        :class="{ expanded: effectivelyExpanded, hidden: !node.children.length }"
        :aria-label="`${effectivelyExpanded ? '收起' : '展开'} ${node.name}`"
        @click="expanded = !expanded"
      ><ChevronRight :size="15" /></button>
      <button class="tree-topic" type="button" @click="emit('select', node.id)">
        <Layers3 :size="16" />
        <span><strong>{{ node.name }}</strong><em>{{ node.activeQuestionCount }} 道题 · {{ node.training.dueCount }} 道到期 · {{ statusLabel }}</em></span>
        <FileQuestion v-if="node.activeQuestionCount" :size="14" />
      </button>
    </div>
    <ul v-if="node.children.length && effectivelyExpanded">
      <KnowledgeTreeNode
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :selected-id="selectedId"
        :matched-ids="matchedIds"
        :expand-all="expandAll"
        @select="emit('select', $event)"
      />
    </ul>
  </li>
</template>
