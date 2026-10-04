<script setup lang="ts">
import type { RanchOperationalControls, RanchRewardSourceType, RanchSourceHealth, RanchSourceRetryAck } from '~/types/ranch-admin'

const { t } = useI18n()
const api = useRanchAdminApi()
const controls = ref<RanchOperationalControls | null>(null)
const health = ref<RanchSourceHealth | null>(null)
const controlsLoading = ref(true)
const healthLoading = ref(true)
const controlsFailed = ref(false)
const healthFailed = ref(false)
const message = ref('')
const reason = ref('')
const eventId = ref('')
const retryReason = ref('')
const retryAck = ref<RanchSourceRetryAck | null>(null)
const sources: RanchRewardSourceType[] = ['ATTENDANCE_RESPONSE', 'TIMELINE_ORIGINAL', 'BLOG_FIRST_PUBLISH', 'PERSONAL_RECALL_COMPLETE']
const retrySource = ref<RanchRewardSourceType>('ATTENDANCE_RESPONSE')
const busy = computed(() => api.command.running.value || !!api.command.pending.value)
const retryValid = computed(() => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(eventId.value)
 && /^[A-Z][A-Z0-9_]{0,79}$/.test(retryReason.value))

async function loadControls() {
 controlsLoading.value = true; controlsFailed.value = false
 try { const result = await api.controls(); if (api.isCurrent()) controls.value = result }
 catch { if (api.isCurrent()) controlsFailed.value = true }
 finally { if (api.isCurrent()) controlsLoading.value = false }
}
async function loadHealth() {
 healthLoading.value = true; healthFailed.value = false
 try { const result = await api.health(); if (api.isCurrent()) health.value = result }
 catch { if (api.isCurrent()) healthFailed.value = true }
 finally { if (api.isCurrent()) healthLoading.value = false }
}
async function saveControls() {
 const current = controls.value
 if (!current || busy.value || !reason.value.trim()) return
 try {
  const saved = await api.saveControls({ version: current.version, isCareEnabled: current.isCareEnabled,
   isShopEnabled: current.isShopEnabled, isDeliveryPaused: current.isDeliveryPaused,
   isRewardsPaused: current.isRewardsPaused, reasonCode: reason.value })
  if (api.isCurrent()) { controls.value = saved; message.value = t('ranch.admin.saved') }
 } catch { if (api.isCurrent()) message.value = t('ranch.command.failed') }
}
async function scheduleRetry() {
 if (busy.value || !retryValid.value) return
 try {
  const saved = await api.retrySource(retrySource.value, eventId.value, retryReason.value)
  if (api.isCurrent()) { retryAck.value = saved; message.value = t(`ranch.admin.${saved.disposition}`) }
 } catch { if (api.isCurrent()) message.value = t('ranch.command.failed') }
}
async function retryUnknownCommand() {
 const pending = api.command.pending.value
 if (!pending) return
 try {
  const saved = await api.retryPending()
  if (!api.isCurrent()) return
  if (pending.path.endsWith('/operational-controls')) controls.value = saved as RanchOperationalControls
  else if (pending.path.includes('/outboxes/')) {
   retryAck.value = saved as RanchSourceRetryAck
   message.value = t(`ranch.admin.${retryAck.value.disposition}`)
   return
  }
  message.value = t('ranch.admin.saved')
 } catch { if (api.isCurrent()) message.value = t('ranch.command.failed') }
}
onMounted(() => { void loadControls(); void loadHealth() })
</script>

<template>
 <div class="space-y-6 min-w-0">
  <PageHeader :title="t('ranch.admin.title')" back-to="/system-admin" />
  <section class="space-y-3 rounded-xl border p-4">
   <h2 class="font-semibold">{{ t('ranch.admin.controls') }}</h2>
   <PageLoading v-if="controlsLoading" />
   <DashboardErrorState v-else-if="controlsFailed" @retry="loadControls" />
   <form v-else-if="controls" class="space-y-3" @submit.prevent="saveControls">
    <p>{{ t('ranch.admin.independent') }}</p>
    <fieldset :disabled="busy" class="grid gap-3">
     <label class="flex min-h-11 items-center gap-2"><input v-model="controls.isCareEnabled" type="checkbox">{{ t('ranch.admin.care') }}</label>
     <label class="flex min-h-11 items-center gap-2"><input v-model="controls.isShopEnabled" type="checkbox">{{ t('ranch.admin.shop') }}</label>
     <label class="flex min-h-11 items-center gap-2"><input v-model="controls.isDeliveryPaused" type="checkbox">{{ t('ranch.admin.deliveryPause') }}</label>
     <label class="flex min-h-11 items-center gap-2"><input v-model="controls.isRewardsPaused" type="checkbox">{{ t('ranch.admin.rewardPause') }}</label>
     <label class="grid gap-1">{{ t('ranch.admin.reason') }}<input v-model="reason" required maxlength="40" class="min-h-11 rounded border bg-transparent p-2"></label>
     <Button type="submit" :disabled="!reason.trim()" :label="t('ranch.save')" class="min-h-11" />
    </fieldset>
   </form>
  </section>
  <section class="space-y-3 rounded-xl border p-4">
   <h2 class="font-semibold">{{ t('ranch.admin.health') }}</h2>
   <PageLoading v-if="healthLoading" />
   <DashboardErrorState v-else-if="healthFailed" @retry="loadHealth" />
   <template v-else-if="health">
    <div v-for="row in health.sources" :key="row.sourceType" class="rounded border p-3">
     <h3 class="font-medium">{{ t(`ranch.admin.source.${row.sourceType}`) }}</h3>
     <dl class="grid gap-2 sm:grid-cols-3">
      <div><dt>{{ t('ranch.admin.pending') }}</dt><dd>{{ row.pendingCount }}</dd></div>
      <div><dt>{{ t('ranch.admin.dead') }}</dt><dd>{{ row.deadCount }}</dd></div>
      <div><dt>{{ t('ranch.admin.oldest') }}</dt><dd>{{ row.oldestAgeSeconds === null ? '—' : t('ranch.admin.seconds', { count: row.oldestAgeSeconds }) }}</dd></div>
     </dl>
    </div>
    <Button :label="t('ranch.admin.refresh')" class="min-h-11" @click="loadHealth" />
   </template>
   <form class="space-y-3" @submit.prevent="scheduleRetry">
    <h3 class="font-medium">{{ t('ranch.admin.retrySource') }}</h3>
    <p>{{ t('ranch.admin.retryNotice') }}</p>
    <fieldset :disabled="busy" class="grid gap-3">
     <label class="grid gap-1">{{ t('ranch.admin.sourceLabel') }}<select v-model="retrySource" class="min-h-11 rounded border bg-transparent p-2"><option v-for="source in sources" :key="source" :value="source">{{ t(`ranch.admin.source.${source}`) }}</option></select></label>
     <label class="grid gap-1">{{ t('ranch.admin.eventId') }}<input v-model="eventId" required maxlength="36" class="min-h-11 rounded border bg-transparent p-2"></label>
     <label class="grid gap-1">{{ t('ranch.admin.reason') }}<input v-model="retryReason" required maxlength="80" pattern="[A-Z][A-Z0-9_]{0,79}" class="min-h-11 rounded border bg-transparent p-2"></label>
     <Button type="submit" :disabled="!retryValid" :label="t('ranch.admin.retrySource')" class="min-h-11" />
    </fieldset>
   </form>
   <p v-if="retryAck" role="status">{{ t(`ranch.admin.${retryAck.disposition}`) }}</p>
  </section>
  <p role="status" aria-live="polite">{{ message }}</p>
  <Button v-if="api.command.pending.value && !api.command.running.value" :label="t('ranch.retry')" class="min-h-11" @click="retryUnknownCommand" />
 </div>
</template>
