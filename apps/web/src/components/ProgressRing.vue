<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ value: number; label: string; caption: string }>()
const normalized = computed(() => Math.min(100, Math.max(0, props.value)))
</script>

<template>
  <div class="progress-ring" :style="{ '--progress': `${normalized * 3.6}deg` }">
    <div class="progress-ring-inner">
      <span>{{ label }}</span>
      <strong>{{ normalized }}%</strong>
      <em>{{ caption }}</em>
    </div>
  </div>
</template>
