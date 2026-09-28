<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, CHANNEL_LABEL, formatTime } from '../api'

const props = defineProps<{ canDecide: boolean }>()

interface RedirectRequest {
  id: string
  shipmentId: string
  oldAddress: string
  newAddress: string
  reason: string | null
  fee: number
  channel: string
  externalRef: string | null
  requestedBy: string
  submittedVia: string
  status: 'PENDING' | 'EXECUTED' | 'REJECTED'
  createdAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
}

const STATUS_LABEL: Record<string, string> = { PENDING: '待執行', EXECUTED: '已執行', REJECTED: '已退回' }
const POLL_MS = 5000

const tab = ref<'PENDING' | 'ALL'>('PENDING')
const rows = ref<RedirectRequest[]>([])
const loadError = ref('')
const lastLoaded = ref('')
const freshIds = ref<Set<string>>(new Set())
const busyId = ref('')
const actionError = ref<Record<string, string>>({})
const rejectingId = ref('')
const rejectReason = ref('')

let known: Set<string> | null = null
let timer: number | undefined

const shown = computed(() => (tab.value === 'PENDING' ? rows.value.filter((r) => r.status === 'PENDING') : rows.value))
const pendingCount = computed(() => rows.value.filter((r) => r.status === 'PENDING').length)
const freshCount = computed(() => rows.value.filter((r) => freshIds.value.has(r.id)).length)

async function load() {
  try {
    const data = await api<RedirectRequest[]>('/api/redirect-requests')
    // 第一次載入不標記「新」；之後輪詢到的新單號才標記
    if (known) {
      const added = data.filter((r) => !known!.has(r.id)).map((r) => r.id)
      if (added.length) {
        const next = new Set(freshIds.value)
        added.forEach((id) => next.add(id))
        freshIds.value = next
      }
    }
    known = new Set(data.map((r) => r.id))
    rows.value = data
    loadError.value = ''
    lastLoaded.value = formatTime(new Date().toISOString())
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  }
}

function markSeen(id: string) {
  if (!freshIds.value.has(id)) return
  const next = new Set(freshIds.value)
  next.delete(id)
  freshIds.value = next
}

async function execute(r: RedirectRequest) {
  busyId.value = r.id
  actionError.value = { ...actionError.value, [r.id]: '' }
  try {
    await api(`/api/redirect-requests/${r.id}/execute`, { method: 'POST' })
    markSeen(r.id)
    await load()
  } catch (e) {
    actionError.value = { ...actionError.value, [r.id]: e instanceof Error ? e.message : String(e) }
  } finally {
    busyId.value = ''
  }
}

function startReject(r: RedirectRequest) {
  rejectingId.value = r.id
  rejectReason.value = ''
  actionError.value = { ...actionError.value, [r.id]: '' }
}

async function confirmReject(r: RedirectRequest) {
  if (!rejectReason.value.trim()) {
    actionError.value = { ...actionError.value, [r.id]: '請填寫退回原因' }
    return
  }
  busyId.value = r.id
  try {
    await api(`/api/redirect-requests/${r.id}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason: rejectReason.value.trim() }),
    })
    rejectingId.value = ''
    markSeen(r.id)
    await load()
  } catch (e) {
    actionError.value = { ...actionError.value, [r.id]: e instanceof Error ? e.message : String(e) }
  } finally {
    busyId.value = ''
  }
}

onMounted(() => {
  void load()
  timer = window.setInterval(load, POLL_MS)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div v-if="freshCount" class="alert new">有 {{ freshCount }} 筆新的改寄申請，已在下方以底色標示。</div>
  <div v-if="!props.canDecide" class="alert info">你沒有物流人員（logistics_staff）權限，只能檢視申請單。</div>
  <div v-if="loadError" class="alert error">載入失敗：{{ loadError }}</div>

  <section class="panel">
    <div class="panel-h">
      <span>改寄申請單</span>
      <span class="tabs" role="group" aria-label="篩選">
        <button type="button" :class="{ on: tab === 'PENDING' }" @click="tab = 'PENDING'">待執行（{{ pendingCount }}）</button>
        <button type="button" :class="{ on: tab === 'ALL' }" @click="tab = 'ALL'">全部（{{ rows.length }}）</button>
      </span>
    </div>
    <div class="panel-b">
      <p class="muted">每 {{ POLL_MS / 1000 }} 秒自動更新，最後更新 {{ lastLoaded || '—' }}</p>
      <div class="scroll">
        <table>
          <thead>
            <tr>
              <th>申請單號</th><th>貨件</th><th>原地址 → 新地址</th><th>手續費</th>
              <th>申請人</th><th>來源管道</th><th>建立時間</th><th>狀態</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!shown.length"><td colspan="9" class="muted">目前沒有資料</td></tr>
            <tr v-for="r in shown" :key="r.id" :class="{ new: freshIds.has(r.id) }">
              <td class="nowrap">{{ r.id }}</td>
              <td class="nowrap">{{ r.shipmentId }}</td>
              <td>
                <span class="muted">{{ r.oldAddress }}</span><br />→ {{ r.newAddress }}
                <div v-if="r.reason" class="muted">原因：{{ r.reason }}</div>
              </td>
              <td class="nowrap">{{ r.fee }} 元</td>
              <td class="nowrap">{{ r.requestedBy }}</td>
              <td class="nowrap">
                <span :class="['label', r.channel === 'AI_COPILOT' ? 'ai' : 'gray']">{{ CHANNEL_LABEL[r.channel] ?? r.channel }}</span>
                <div class="muted">經由 {{ r.submittedVia }}</div>
              </td>
              <td class="nowrap">{{ formatTime(r.createdAt) }}</td>
              <td class="nowrap">
                <span :class="['label', r.status]">{{ STATUS_LABEL[r.status] }}</span>
                <div v-if="r.decidedBy" class="muted">{{ r.decidedBy }}・{{ formatTime(r.decidedAt) }}</div>
                <div v-if="r.decisionNote" class="muted">{{ r.decisionNote }}</div>
              </td>
              <td>
                <template v-if="r.status === 'PENDING' && props.canDecide">
                  <button class="btn success" type="button" :disabled="busyId === r.id" @click="execute(r)">執行</button>
                  <button class="btn danger" type="button" :disabled="busyId === r.id" @click="startReject(r)">退回</button>
                  <div v-if="rejectingId === r.id" class="inline-form">
                    <label class="muted" :for="`reason-${r.id}`">退回原因</label>
                    <input :id="`reason-${r.id}`" v-model="rejectReason" type="text" placeholder="例如：貨件已交配送，無法攔截" @input="actionError = { ...actionError, [r.id]: '' }" />
                    <button class="btn danger" type="button" :disabled="busyId === r.id" @click="confirmReject(r)">確認退回</button>
                    <button class="btn plain" type="button" @click="rejectingId = ''">取消</button>
                  </div>
                </template>
                <span v-else class="muted">—</span>
                <div v-if="actionError[r.id]" class="err" role="alert">{{ actionError[r.id] }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>
