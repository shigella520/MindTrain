<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BookOpenCheck, LayoutDashboard, Menu, Settings, ShieldCheck, X } from '@lucide/vue'
import { useRoute } from 'vue-router'
import GithubIcon from './GithubIcon.vue'
import { useConfigStore } from '../stores/config'

const config = useConfigStore()
const route = useRoute()
const menuOpen = ref(false)

function closeMenu() {
  menuOpen.value = false
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeMenu()
}

watch(() => route.fullPath, closeMenu)
watch(menuOpen, (open) => document.body.classList.toggle('mobile-nav-open', open))
onMounted(() => document.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleKeydown)
  document.body.classList.remove('mobile-nav-open')
})
</script>

<template>
  <header class="topbar reveal" :class="{ 'menu-open': menuOpen }">
    <RouterLink class="brand-mark" to="/" aria-label="MindTrain Dashboard">
      <img class="brand-icon" src="/icon-192.png" alt="" />
      <span class="brand-text">MindTrain Dashboard</span>
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
      <a class="icon-button github-icon" href="https://github.com/shigella520/MindTrain" target="_blank" rel="noreferrer" aria-label="在 GitHub 打开 MindTrain" title="GitHub">
        <GithubIcon />
      </a>
      <RouterLink class="icon-button" to="/settings" title="实例设置"><Settings :size="18" /></RouterLink>
      <button
        class="mobile-menu-button"
        type="button"
        aria-label="切换导航菜单"
        aria-controls="mobile-navigation"
        :aria-expanded="menuOpen"
        @click="menuOpen = !menuOpen"
      >
        <X v-if="menuOpen" :size="20" />
        <Menu v-else :size="20" />
      </button>
    </div>
  </header>
  <button v-if="menuOpen" class="mobile-nav-backdrop" type="button" aria-label="关闭导航菜单" @click="closeMenu"></button>
  <nav id="mobile-navigation" class="mobile-nav-drawer" :class="{ open: menuOpen }" aria-label="移动端主导航" :aria-hidden="!menuOpen" :inert="!menuOpen">
    <RouterLink to="/" @click="closeMenu"><LayoutDashboard :size="18" />看板</RouterLink>
    <RouterLink to="/train" @click="closeMenu"><BookOpenCheck :size="18" />训练</RouterLink>
    <RouterLink to="/admin" @click="closeMenu"><ShieldCheck :size="18" />管理</RouterLink>
    <RouterLink to="/settings" @click="closeMenu"><Settings :size="18" />实例设置</RouterLink>
  </nav>
</template>
