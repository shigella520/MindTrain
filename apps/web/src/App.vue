<script setup lang="ts">
import AppHeader from './components/AppHeader.vue'
</script>

<template>
  <div class="app-shell">
    <div class="backdrop-grid" aria-hidden="true"></div>
    <div class="backdrop-glow glow-left" aria-hidden="true"></div>
    <div class="backdrop-glow glow-right" aria-hidden="true"></div>
    <AppHeader />
    <RouterView />
  </div>
</template>
