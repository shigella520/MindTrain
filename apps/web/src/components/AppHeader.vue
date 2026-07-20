<script setup lang="ts">
import { BookOpenCheck, Code2, LayoutDashboard, Settings, ShieldCheck } from '@lucide/vue'
import { useConfigStore } from '../stores/config'

const config = useConfigStore()
</script>

<template>
  <header class="topbar reveal">
    <RouterLink class="brand-mark" to="/" aria-label="MindTrain Dashboard">
      <span class="brand-dot"></span>
      <span class="brand-text">MindTrain</span>
    </RouterLink>
    <nav class="primary-nav" aria-label="主导航">
      <RouterLink to="/"><LayoutDashboard :size="17" />看板</RouterLink>
      <RouterLink to="/train"><BookOpenCheck :size="17" />训练</RouterLink>
      <RouterLink to="/admin"><ShieldCheck :size="17" />管理</RouterLink>
    </nav>
    <div class="topbar-meta">
      <span class="connection-pill" :class="{ online: config.configured }">
        <span class="status-dot"></span>{{ config.configured ? '实例已配置' : '等待配置' }}
      </span>
      <a class="icon-button" href="https://github.com/shigella520/MindTrain" target="_blank" rel="noreferrer" title="GitHub">
        <Code2 :size="18" />
      </a>
      <RouterLink class="icon-button" to="/settings" title="实例设置"><Settings :size="18" /></RouterLink>
    </div>
  </header>
</template>
