<script setup lang="ts">
import type { DiagnosisResult } from '~/types/ranch'
const { t, locale } = useI18n(); useHead({ title: t('ranch.diagnosisResults.title') })
const { formatDateTime } = useDatetime()
const route = useRoute(); const api = useDiagnosisApi(); const ranch = useRanchState(); const { handleApiError } = useErrorHandler()
const result = ref<DiagnosisResult | null>(null); const loading = ref(false); const failed = ref(false); const message = ref('')
const ranchLoading = ref(false); const ranchFailed = ref(false)
let resultRun = 0; let ranchRun = 0
async function loadRanch() {
 const run = ++ranchRun
 ranchLoading.value = true; ranchFailed.value = false
 try { await ranch.load() } catch (error) {
  if (api.isCurrent() && run === ranchRun) { ranchFailed.value = true; handleApiError(error, 'DiagnosisResultRanch') }
 } finally { if (api.isCurrent() && run === ranchRun) ranchLoading.value = false }
}
async function load() {
 const run = ++resultRun
 loading.value = true; failed.value = false; result.value = null
 try {
  const value = await api.result(String(route.params.resultId))
  if (!api.isCurrent() || run !== resultRun) return
  result.value = value
  void loadRanch()
 } catch (error) {
  if (api.isCurrent() && run === resultRun) { failed.value = true; handleApiError(error, 'DiagnosisResult') }
 } finally { if (api.isCurrent() && run === resultRun) loading.value = false }
}
async function assign() {
 const value = result.value; const version = ranch.state.value?.owner?.version; if (!value || !version || value.method !== 'DIAGNOSIS') return
 try { await ranch.act(() => ranch.api.assignment({ method: 'DIAGNOSIS', resultId: value.id, version })); if (api.isCurrent()) await navigateTo('/my/ranch') } catch { if (api.isCurrent()) message.value = t('ranch.command.failed') }
}
const assignmentAvailable = computed(() => !ranchLoading.value && !ranchFailed.value && !!result.value && !!ranch.state.value?.assignment?.availableMethods.includes(result.value.method))
onMounted(load)
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.diagnosisResults.title')" back-to="/my/ranch/results" />
  <PageLoading v-if="loading" />
  <DashboardErrorState v-else-if="failed" @retry="load" />
  <SectionCard v-else-if="result" :title="t(result.method === 'DIAGNOSIS' ? 'ranch.diagnosisResults.type64' : 'ranch.diagnosisResults.birthStyle')">
   <p>{{ t('ranch.diagnosisResults.completedAt', { date: formatDateTime(result.completedAt) }) }}</p>
   <p class="whitespace-pre-wrap my-3">{{ result.descriptionSnapshot[locale] ?? result.descriptionSnapshot.ja }}</p>
   <RanchAxisResults v-if="result.method === 'DIAGNOSIS'" :result="result" />
   <p v-if="result.typeCode">{{ t('ranch.diagnosisResults.typeCode', { code: result.typeCode }) }}</p>
   <p v-if="result.numberSummary">{{ t('ranch.birth.numbers', { life: result.numberSummary.lifePathNumber, name: result.numberSummary.nameNumber }) }}</p>
   <ul v-if="result.axisDescriptions" class="space-y-2 my-3"><li v-for="(description,axis) in result.axisDescriptions" :key="axis">{{ description[locale] ?? description.ja }}</li></ul>
   <p>{{ t('ranch.diagnosisResults.avatarUnchanged') }}</p>
   <PageLoading v-if="ranchLoading" /><DashboardErrorState v-else-if="ranchFailed || ranch.failed.value" @retry="loadRanch" />
   <p v-else-if="!assignmentAvailable" class="mt-3" role="status">{{ t('ranch.assignment.mappingPending') }}</p>
   <Button v-if="result.method === 'DIAGNOSIS' && assignmentAvailable && ranch.state.value?.dinosaur?.stage === 'EGG'" class="mt-3 min-h-11" :label="t('ranch.assignment.useResult')" @click="assign" />
   <NuxtLink v-if="result.method === 'BIRTH_STYLE' && assignmentAvailable && ranch.state.value?.dinosaur?.stage === 'EGG'" to="/my/ranch/birth-profile" class="flex min-h-11 items-center text-primary">{{ t('ranch.birth.reconfirm') }}</NuxtLink>
   <p role="status">{{ message }}</p>
  </SectionCard>
 </div>
</template>
