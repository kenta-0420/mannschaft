<script setup lang="ts">
import type { DiagnosisResult } from '~/types/ranch'
definePageMeta({ middleware: 'auth' })
const { t, locale } = useI18n(); useHead({ title: t('ranch.diagnosisResults.title') })
const route = useRoute(); const api = useDiagnosisApi(); const ranch = useRanchState(); const { handleApiError } = useErrorHandler()
const result = ref<DiagnosisResult | null>(null); const loading = ref(false); const failed = ref(false); const message = ref('')
async function load() { loading.value = true; failed.value = false; try { result.value = await api.result(String(route.params.resultId)); await ranch.load() } catch(error) { result.value = null; failed.value = true; handleApiError(error, 'DiagnosisResult') } finally { loading.value = false } }
async function assign() {
 const value = result.value; const version = ranch.state.value?.owner?.version; if (!value || !version || value.method !== 'DIAGNOSIS') return
 try { await ranch.act(() => ranch.api.assignment({ method: 'DIAGNOSIS', resultId: value.id, version })); await navigateTo('/my/ranch') } catch { message.value = t('ranch.command.failed') }
}
onMounted(load)
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.diagnosisResults.title')" back-to="/my/ranch/results" />
  <PageLoading v-if="loading" />
  <DashboardErrorState v-else-if="failed" @retry="load" />
  <SectionCard v-else-if="result" :title="t(result.method === 'DIAGNOSIS' ? 'ranch.diagnosisResults.type64' : 'ranch.diagnosisResults.birthStyle')">
   <p>{{ t('ranch.diagnosisResults.completedAt', { date: result.completedAt }) }}</p>
   <p class="whitespace-pre-wrap my-3">{{ result.descriptionSnapshot[locale] ?? result.descriptionSnapshot.ja }}</p>
   <p v-if="result.typeCode">{{ t('ranch.diagnosisResults.typeCode', { code: result.typeCode }) }}</p>
   <p v-if="result.numberSummary">{{ t('ranch.birth.numbers', { life: result.numberSummary.lifePathNumber, name: result.numberSummary.nameNumber }) }}</p>
   <ul v-if="result.axisDescriptions" class="space-y-2 my-3"><li v-for="(description,axis) in result.axisDescriptions" :key="axis">{{ description[locale] ?? description.ja }}</li></ul>
   <p>{{ t('ranch.diagnosisResults.avatarUnchanged') }}</p>
   <p v-if="!result.mappingVersion" class="mt-3" role="status">{{ t('ranch.assignment.mappingPending') }}</p>
   <Button v-if="result.method === 'DIAGNOSIS' && result.mappingVersion && ranch.state.value?.dinosaur?.stage === 'EGG'" class="mt-3 min-h-11" :label="t('ranch.assignment.useResult')" @click="assign" />
   <NuxtLink v-if="result.method === 'BIRTH_STYLE' && result.mappingVersion && ranch.state.value?.dinosaur?.stage === 'EGG'" to="/my/ranch/birth-profile" class="flex min-h-11 items-center text-primary">{{ t('ranch.birth.reconfirm') }}</NuxtLink>
   <p role="status">{{ message }}</p>
  </SectionCard>
 </div>
</template>
