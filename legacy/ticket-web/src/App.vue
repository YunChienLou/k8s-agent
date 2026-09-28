<script setup lang="ts">
import { onMounted, ref } from 'vue'
import CompensationApprovals from './views/CompensationApprovals.vue'
import { logout, username } from './auth'
import { myProfile } from './api'

const roles = ref<string[]>([])
const profileError = ref('')

onMounted(async () => {
  try {
    const p = await myProfile()
    roles.value = p.systems['ticket'] ?? []
  } catch (e) {
    profileError.value = e instanceof Error ? e.message : String(e)
  }
})
</script>

<template>
  <div class="layout">
    <nav class="side" aria-label="選單">
      <div class="brand">客服工單系統<small>CS Ticket v4.1</small></div>
      <a href="#" class="active">補償簽核</a>
    </nav>
    <div class="main">
      <header class="topbar">
        <span>首頁 / 補償管理 / 補償簽核</span>
        <span class="who">
          登入者 <b>{{ username() }}</b>
          <template v-if="roles.length">（{{ roles.join('、') }}）</template>
          <button class="btn plain" type="button" @click="logout">登出</button>
        </span>
      </header>
      <main class="content">
        <div v-if="profileError" class="alert error">無法取得權限資料：{{ profileError }}</div>
        <CompensationApprovals :can-decide="roles.includes('cs_supervisor')" />
      </main>
    </div>
  </div>
</template>
