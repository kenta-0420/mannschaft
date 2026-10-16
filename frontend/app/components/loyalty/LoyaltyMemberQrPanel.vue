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
let timer: ReturnType<typeof setInterval> | undefined
async function load() {
  if (loading.value) return
  loading.value = true
  qr.value = null
  error.value = null
  try {
    const card = await api.ensurePlatform()
    qr.value = await api.getQr(card.memberCardId)
    seconds.value = qr.value.expiresIn
    deadline = Date.now() + seconds.value * 1000
  } catch (e) { error.value = e; handleApiError(e) }
  finally { loading.value = false }
}
function tick() {
  seconds.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
  if (qr.value && seconds.value === 0) { qr.value = null; void load() }
}
function resume() { if (!document.hidden) { qr.value = null; void load() } }
onMounted(() => { void load(); timer = setInterval(tick, 1000); document.addEventListener('visibilitychange', resume) })
onUnmounted(() => { clearInterval(timer); document.removeEventListener('visibilitychange', resume) })
</script>

<template>
  <SectionCard :title="t('loyalty.memberQr.title')">
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <div v-else-if="qr && seconds > 0" class="flex min-w-0 flex-col items-center gap-3" data-testid="member-qr">
      <p>{{ qr.displayName }}</p>
      <QrCodeImage :value="qr.qrToken" :size="280" :aria-label="t('loyalty.memberQr.title')" class="max-w-full" />
      <p role="status">{{ t('loyalty.memberQr.expiresIn', { seconds }) }}</p>
    </div>
    <Button class="mt-3 min-h-11" :label="t('loyalty.refresh')" :disabled="loading" icon="pi pi-refresh" @click="load" />
  </SectionCard>
</template>
