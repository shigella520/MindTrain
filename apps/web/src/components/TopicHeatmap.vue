<script setup lang="ts">
import type { WeakTopic } from '../types/api'

defineProps<{ topics: WeakTopic[] }>()

function level(score: number) {
  if (score < 35) return 1
  if (score < 55) return 2
  if (score < 75) return 3
  return 4
}
</script>

<template>
  <div v-if="topics.length" class="topic-heatmap">
    <article v-for="topic in topics" :key="topic.topic_id" :class="`heat-level-${level(topic.mastery_score)}`">
      <span>{{ topic.name || topic.topic_id }}</span>
      <strong>{{ topic.mastery_score }}</strong>
      <em>{{ topic.wrong_count }} 次错误</em>
    </article>
  </div>
  <div v-else class="empty-panel">
    <strong>还没有掌握度数据</strong>
    <span>完成第一轮训练后，这里会形成你的知识热力图。</span>
  </div>
</template>
