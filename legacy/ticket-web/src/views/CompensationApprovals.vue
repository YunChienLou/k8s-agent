<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, CHANNEL_LABEL, formatTime } from '../api'

const props = defineProps<{ canDecide: boolean }>()

interface CompensationRequest {
  id: string
  ticketId: string
  customerId: string
  type: 'SHIPPING_FEE_REFUND' | 'COUPON'
  amount: number
  reason: string
  channel: string
  externalRef: string | null
  requestedBy: string
  submittedVia: string
  status: 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'
  createdAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
  compensationId: string | null
}

const STATUS_LABEL: Record<string, string> = { PENDING_APPROVAL: '待簽核', APPROVED: '已核准', REJECTED: '已駁回' }
const TYPE_LABEL: Record<string, string> = { SHIPPING_FEE_REFUND: '運費退還', COUPON: '折價券' }
const POLL_MS = 5000

const tab = ref<'PENDING' | 'ALL'>('PENDING')
const rows = ref<CompensationRequest[]>([])
const loadError = ref('')
const lastLoaded = ref('')
const freshIds = ref<Set<string>>(new Set())
const busyId = ref('')
const actionError = ref<Record<string, string>>({})
const rejectingId = ref('')
const rejectReason = ref('')

let known: Set<string> | null = null
let timer: number | undefined

const shown = computed(() =>
  tab.value === 'PENDING' ? rows.value.filter((r) => r.status === 'PENDING_APPROVAL') : rows.value,
)
const pendingCount = computed(() => rows.value.filter((r) => r.status === 'PENDING_APPROVAL').length)
const freshCount = computed(() => rows.value.filter((r) => freshIds.value.has(r.id)).length)

async function load() {
  try {
    const data = await api<CompensationRequest[]>('/api/compensation-requests')
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

function setError(id: string, msg: string) {
  actionError.value = { ...actionError.value, [id]: msg }
}

async function approve(r: CompensationRequest) {
  busyId.value = r.id
  setError(r.id, '')
  try {
    await api(`/api/compensation-requests/${r.id}/approve`, { method: 'POST' })
    markSeen(r.id)
    await load()
  } catch (e) {
    setError(r.id, e instanceof Error ? e.message : String(e))
  } finally {
    busyId.value = ''
  }
}

function startReject(r: CompensationRequest) {
  rejectingId.value = r.id
  rejectReason.value = ''
  setError(r.id, '')
}

async function confirmReject(r: CompensationRequest) {
  if (!rejectReason.value.trim()) {
    setError(r.id, '請填寫駁回原因')
    return
  }
  busyId.value = r.id
  try {
    await api(`/api/compensation-requests/${r.id}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason: rejectReason.value.trim() }),
    })
    rejectingId.value = ''
    markSeen(r.id)
    await load()
  } catch (e) {
    setError(r.id, e instanceof Error ? e.message : String(e))
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
  <div v-if="freshCount" class="alert new">有 {{ freshCount }} 筆新的補償申請，已在下方以底色標示。</div>
  <div v-if="!props.canDecide" class="alert info">你沒有客服主管（cs_supervisor）權限，只能檢視自己提出的申請。</div>
  <div v-if="loadError" class="alert error">載入失敗：{{ loadError }}</div>

  <section class="panel">
    <div class="panel-h">
      <span>補償申請（超過客服人員上限，需主管簽核）</span>
      <span class="tabs" role="group" aria-label="篩選">
        <button type="button" :class="{ on: tab === 'PENDING' }" @click="tab = 'PENDING'">待簽核（{{ pendingCount }}）</button>
        <button type="button" :class="{ on: tab === 'ALL' }" @click="tab = 'ALL'">全部（{{ rows.length }}）</button>
      </span>
    </div>
    <div class="panel-b">
      <p class="muted">每 {{ POLL_MS / 1000 }} 秒自動更新，最後更新 {{ lastLoaded || '—' }}</p>
      <div class="scroll">
        <table>
          <thead>
            <tr>
              <th>申請號</th><th>工單</th><th>類型</th><th>金額</th><th>原因</th>
              <th>申請人</th><th>來源管道</th><th>建立時間</th><th>狀態</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!shown.length"><td colspan="10" class="muted">目前沒有資料</td></tr>
            <tr v-for="r in shown" :key="r.id" :class="{ new: freshIds.has(r.id) }">
              <td class="nowrap">{{ r.id }}</td>
              <td class="nowrap">{{ r.ticketId }}</td>
              <td class="nowrap">{{ TYPE_LABEL[r.type] ?? r.type }}</td>
              <td class="nowrap">{{ r.amount }} 元</td>
              <td>{{ r.reason }}</td>
              <td class="nowrap">{{ r.requestedBy }}</td>
              <td class="nowrap">
                <span :class="['label', r.channel === 'AI_COPILOT' ? 'ai' : 'gray']">{{ CHANNEL_LABEL[r.channel] ?? r.channel }}</span>
                <div class="muted">經由 {{ r.submittedVia }}</div>
              </td>
              <td class="nowrap">{{ formatTime(r.createdAt) }}</td>
              <td class="nowrap">
                <span :class="['label', r.status]">{{ STATUS_LABEL[r.status] }}</span>
                <div v-if="r.decidedBy" class="muted">{{ r.decidedBy }}・{{ formatTime(r.decidedAt) }}</div>
                <div v-if="r.compensationId" class="muted">補償編號 {{ r.compensationId }}</div>
                <div v-if="r.decisionNote" class="muted">{{ r.decisionNote }}</div>
              </td>
              <td>
                <template v-if="r.status === 'PENDING_APPROVAL' && props.canDecide">
                  <button class="btn success" type="button" :disabled="busyId === r.id" @click="approve(r)">核准</button>
                  <button class="btn danger" type="button" :disabled="busyId === r.id" @click="startReject(r)">駁回</button>
                  <div v-if="rejectingId === r.id" class="inline-form">
                    <label class="muted" :for="`reason-${r.id}`">駁回原因</label>
                    <input :id="`reason-${r.id}`" v-model="rejectReason" type="text" placeholder="例如：金額過高，請改為 100 元" @input="setError(r.id, '')" />
                    <button class="btn danger" type="button" :disabled="busyId === r.id" @click="confirmReject(r)">確認駁回</button>
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
