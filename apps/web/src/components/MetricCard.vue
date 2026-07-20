<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  note: string
  tone?: 'blue' | 'mint' | 'peach' | 'lilac'
}>()
</script>

<template>
  <article class="metric-card" :class="`tone-${tone || 'blue'}`">
    <span>{{ label }}</span>
    <strong>{{ value }}</strong>
    <em>{{ note }}</em>
  </article>
</template>
