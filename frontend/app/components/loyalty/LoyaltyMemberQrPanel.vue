<script setup lang="ts">
import type { MemberCardQr } from '~/types/member-card'
const { t } = useI18n()
const api = useMemberCardApi()
const { handleApiError } = useErrorHandler()
const qr = ref<MemberCardQr | null>(null)
const loading = ref(false)
const error = shallowRef<unknown>(null)
const seconds = ref(0)
let deadline = 0
let generation = 0
let timer: ReturnType<typeof setInterval> | undefined
let expiryTimer: ReturnType<typeof setTimeout> | undefined
async function load() {
  if (loading.value) return
  clearTimeout(expiryTimer)
  const requestGeneration = ++generation
  loading.value = true
  qr.value = null
  error.value = null
  try {
    const card = await api.ensurePlatform()
    if (requestGeneration !== generation) return
    // 応答受信時からTTLを数えると通信遅延ぶん署名期限を超えるため、要求開始時を基準にする。
    const requestedAt = Date.now()
    const nextQr = await api.getQr(card.memberCardId)
    if (requestGeneration !== generation || document.hidden) return
    deadline = requestedAt + nextQr.expiresIn * 1000
    seconds.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
    qr.value = seconds.value > 0 ? nextQr : null
    if (qr.value) {
      // 残秒表示のintervalとは別に、要求開始時からの期限ちょうどに旧QRを隠す。
      expiryTimer = setTimeout(() => {
        if (requestGeneration === generation) tick()
      }, Math.max(0, deadline - Date.now()))
    }
  } catch (e) {
    if (requestGeneration !== generation) return
    error.value = e
    handleApiError(e, 'team-loyalty')
  } finally {
    if (requestGeneration === generation) loading.value = false
  }
}
function tick() {
  seconds.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
  if (qr.value && seconds.value === 0) {
    qr.value = null
    void load()
  }
}
function resume() {
  clearTimeout(expiryTimer)
  generation++
  qr.value = null
  seconds.value = 0
  loading.value = false
  if (!document.hidden) void load()
}
onMounted(() => {
  void load()
  timer = setInterval(tick, 1000)
  document.addEventListener('visibilitychange', resume)
})
onUnmounted(() => {
  generation++
  clearInterval(timer)
  clearTimeout(expiryTimer)
  document.removeEventListener('visibilitychange', resume)
})
</script>

<template>
  <SectionCard :title="t('loyalty.memberQr.title')">
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <div
      v-else-if="qr && seconds > 0"
      class="flex min-w-0 flex-col items-center gap-3"
      data-testid="member-qr"
    >
      <p>{{ qr.displayName }}</p>
      <QrCodeImage
        :value="qr.qrToken"
        :size="280"
        error-correction-level="Q"
        :margin="4"
        center-label=""
        role="img"
        :aria-label="t('loyalty.memberQr.title')"
        class="max-w-full"
      />
      <p role="status">{{ t('loyalty.memberQr.expiresIn', { seconds }) }}</p>
    </div>
    <p v-else role="status">{{ t('loyalty.memberQr.expired') }}</p>
    <Button
      class="mt-3 min-h-11"
      :label="t('loyalty.refresh')"
      :disabled="loading"
      icon="pi pi-refresh"
      @click="load"
    />
  </SectionCard>
</template>
