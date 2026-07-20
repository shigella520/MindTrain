<script setup lang="ts">
import { ref } from 'vue'
import { CheckCircle2, KeyRound, Link2, LoaderCircle, Trash2 } from '@lucide/vue'
import { coreApi } from '../services/core'
import { useConfigStore } from '../stores/config'

const config = useConfigStore()
const apiBaseUrl = ref(config.apiBaseUrl)
const token = ref(config.token)
const state = ref<'idle' | 'testing' | 'success' | 'error'>('idle')
const message = ref('')

async function saveAndTest() {
  config.save({ apiBaseUrl: apiBaseUrl.value, token: token.value })
  state.value = 'testing'
  message.value = ''
  try {
    const overview = await coreApi.overview()
    state.value = 'success'
    message.value = `连接成功，当前已有 ${overview.activeQuestions} 道可复习题。`
  } catch (cause) {
    state.value = 'error'
    message.value = cause instanceof Error ? cause.message : '连接失败'
  }
}

function clear() {
  config.clear()
  apiBaseUrl.value = config.apiBaseUrl
  token.value = ''
  state.value = 'idle'
  message.value = '浏览器中的实例配置已清除。'
}
</script>

<template>
  <main class="settings-page page-container narrow-page">
    <section class="settings-card reveal">
      <div class="settings-head">
        <div><p class="eyebrow">PRIVATE INSTANCE</p><h1>连接你的 MindTrain</h1><p>Web 通过 Training Core REST API 读取看板并提交训练结果。</p></div>
        <span class="settings-lock"><KeyRound :size="24" /></span>
      </div>
      <form @submit.prevent="saveAndTest">
        <label>
          <span><Link2 :size="17" />Training Core API</span>
          <input v-model="apiBaseUrl" type="text" placeholder="/api/v1 或 https://train.example.com/api/v1" autocomplete="url" />
          <em>同源部署推荐保留 `/api/v1`，由 Web 反向代理访问 Core。</em>
        </label>
        <label>
          <span><KeyRound :size="17" />Bootstrap Token</span>
          <input v-model="token" type="password" placeholder="部署时设置的单用户 Token" autocomplete="current-password" />
          <em>Token 仅保存在当前浏览器 localStorage。请只在自己的设备上使用。</em>
        </label>
        <div class="settings-actions">
          <button class="button danger-ghost" type="button" @click="clear"><Trash2 :size="17" />清除</button>
          <button class="button primary" type="submit" :disabled="state === 'testing' || !token.trim()">
            <LoaderCircle v-if="state === 'testing'" class="spinning" :size="17" />
            <CheckCircle2 v-else :size="17" />保存并测试
          </button>
        </div>
      </form>
      <p v-if="message" class="settings-feedback" :class="state">{{ message }}</p>
    </section>
  </main>
</template>
