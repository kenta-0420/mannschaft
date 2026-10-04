<script setup lang="ts">
import type { RanchSettings } from '~/types/ranch'

const { t } = useI18n(); useHead({ title: t('ranch.title') })
const ranch = useRanchState(); const visibility = useRanchVisibility()
const message = ref(''); const visibilityFailed = ref(false)
const busy = computed(() => ranch.loading.value || ranch.api.command.running.value)
async function changeVisibility(visible: boolean) {
 visibilityFailed.value = false
 try { await visibility.setVisible(visible); await ranch.load() } catch { visibilityFailed.value = true; message.value = t('ranch.settings.visibilityFailed') }
}
async function start() {
 try { await ranch.act(ranch.api.start); await changeVisibility(true) } catch { message.value = t('ranch.command.failed') }
}
async function saveSettings(settings: Omit<RanchSettings,'isVisible' | 'viewMode'>) { try { await ranch.act(() => ranch.api.settings(settings)) } catch { message.value = t('ranch.command.failed') } }
async function participation(action: 'pause' | 'resume') { const version = ranch.state.value?.owner?.version; if (version) { try { await ranch.act(() => ranch.api.participation(action, version)) } catch { message.value = t('ranch.command.failed') } } }
async function retryCommand() {
 const pending = ranch.api.command.pending.value
 const retryingStart = pending?.path === '/api/v1/me/ranch' && pending.method === 'POST'
 try {
  await ranch.act(ranch.api.retryPending)
  if (retryingStart) await changeVisibility(true)
 } catch { message.value = t('ranch.command.failed') }
}
async function hatch(name: string) { const version = ranch.state.value?.owner?.version; if (version) { try { await ranch.act(() => ranch.api.hatch(version, name)) } catch { message.value = t('ranch.command.failed') } } }
onMounted(ranch.load)
</script>
<template>
 <div class="space-y-5 min-w-0">
  <PageHeader :title="t('ranch.title')" back-to="/settings" />
  <NuxtLink to="/my/ranch/results" class="inline-flex min-h-11 items-center text-primary">{{ t('ranch.diagnosisResults.title') }}</NuxtLink>
  <PageLoading v-if="ranch.loading.value" />
  <DashboardErrorState v-else-if="ranch.failed.value" @retry="ranch.load" />
  <template v-else-if="ranch.state.value">
   <WidgetRanch v-if="ranch.state.value.dinosaur" :key="ranch.state.value.owner?.version" :active="true" />
   <RanchStatusPanel :state="ranch.state.value" />
   <Button v-if="ranch.state.value.owner" class="min-h-11" :disabled="busy" :label="t('ranch.admin.refresh')" @click="ranch.load" />
   <p v-if="ranch.state.value.dinosaur?.egg" role="status">{{ t(`ranch.egg.${ranch.state.value.dinosaur.egg.crackStage}`) }}</p>
   <NuxtLink v-if="ranch.state.value.dinosaur?.stage === 'EGG'" to="/my/ranch/assignment" class="inline-flex min-h-11 items-center text-primary">{{ t('ranch.care.choose') }}</NuxtLink>
   <RanchNaming v-if="ranch.state.value.dinosaur?.egg?.hatchReady && ranch.state.value.assignment?.selectionConfirmed" :busy="busy" :retry-required="!!ranch.api.command.pending.value" @confirm="hatch" />
   <RanchSettingsPanel :state="ranch.state.value" :busy="busy || !!ranch.api.command.pending.value" @start="start" @settings="saveSettings" @participation="participation" @visibility="changeVisibility" />
   <NuxtLink v-if="ranch.state.value.owner" to="/my/ranch/decorations" class="inline-flex min-h-11 items-center text-primary">{{ t('ranch.decorations.title') }}</NuxtLink>
   <NuxtLink v-if="ranch.state.value.owner" to="/my/ranch/records" class="inline-flex min-h-11 items-center ml-4 text-primary">{{ t('ranch.records.title') }}</NuxtLink>
   <NuxtLink to="/reflections/recall" class="inline-flex min-h-11 items-center ml-4 text-primary">{{ t('ranch.recall') }}</NuxtLink>
  </template>
  <p role="status" aria-live="polite">{{ message }}</p>
  <Button v-if="ranch.api.command.pending.value && !ranch.api.command.running.value" class="min-h-11" :label="t('ranch.retry')" @click="retryCommand" />
  <Button v-if="visibilityFailed" class="min-h-11" :label="t('ranch.retry')" @click="changeVisibility(true)" />
 </div>
</template>
