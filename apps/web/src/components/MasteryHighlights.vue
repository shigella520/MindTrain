<script setup lang="ts">
import type { MasteryTopic } from '../types/api'

defineProps<{
  weakTopics: MasteryTopic[]
  strongTopics: MasteryTopic[]
  insufficientEvidenceTopicCount: number
}>()
</script>

<template>
  <div class="mastery-board">
    <section class="mastery-column weak">
      <header><div><span class="mastery-dot"></span><strong>待加强</strong></div><em>{{ weakTopics.length }}</em></header>
      <div v-if="weakTopics.length" class="mastery-topic-list">
        <article v-for="topic in weakTopics" :key="topic.topic_id">
          <div class="mastery-topic-head"><span>{{ topic.topic_path }}</span><strong>{{ topic.mastery_score }}</strong></div>
          <p>{{ topic.domain_name }} · {{ topic.correct_count }} 正确 / {{ topic.wrong_count }} 错误</p>
          <div class="mastery-track"><i :style="{ width: `${topic.mastery_score}%` }"></i></div>
        </article>
      </div>
      <div v-else class="mastery-empty"><strong>暂无待加强内容</strong><span>达到最低样本量后才会参与判断。</span></div>
    </section>

    <section class="mastery-column strong">
      <header><div><span class="mastery-dot"></span><strong>擅长</strong></div><em>{{ strongTopics.length }}</em></header>
      <div v-if="strongTopics.length" class="mastery-topic-list">
        <article v-for="topic in strongTopics" :key="topic.topic_id">
          <div class="mastery-topic-head"><span>{{ topic.topic_path }}</span><strong>{{ topic.mastery_score }}</strong></div>
          <p>{{ topic.domain_name }} · {{ topic.correct_count }} 正确 / {{ topic.wrong_count }} 错误</p>
          <div class="mastery-track"><i :style="{ width: `${topic.mastery_score}%` }"></i></div>
        </article>
      </div>
      <div v-else class="mastery-empty"><strong>尚未形成擅长项</strong><span>至少 3 次作答且掌握度达到 75。</span></div>
    </section>
  </div>

  <p v-if="insufficientEvidenceTopicCount" class="mastery-evidence-note">
    另有 {{ insufficientEvidenceTopicCount }} 个知识点正在积累样本，暂不归入双榜。
  </p>
</template>
