<script setup lang="ts">
import type { CursorPage, DiagnosisResult } from '~/types/ranch'
definePageMeta({ middleware: 'auth' })
const { t } = useI18n(); useHead({ title: t('ranch.diagnosisResults.title') })
const api = useDiagnosisApi(); const { handleApiError } = useErrorHandler(); const route = useRoute()
const page = ref<CursorPage<DiagnosisResult> | null>(null); const loading = ref(false); const failed = ref(false)
async function load(cursor?: string) { loading.value = true; failed.value = false; try { page.value = await api.results(undefined,cursor) } catch(error) { page.value = null; failed.value = true; handleApiError(error, 'DiagnosisResults') } finally { loading.value = false } }
onMounted(() => load())
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.diagnosisResults.title')" back-to="/my/ranch" />
  <p>{{ t('ranch.diagnosisResults.avatarUnchanged') }}</p>
  <NuxtLink v-if="typeof route.query.session === 'string'" :to="{ path: '/my/ranch/diagnosis', query: { session: route.query.session } }" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosis.resume') }}</NuxtLink>
  <PageLoading v-if="loading" />
  <DashboardErrorState v-else-if="failed" @retry="load()" />
  <template v-else-if="page">
   <SectionCard v-for="method in (['DIAGNOSIS','BIRTH_STYLE'] as const)" :key="method" :title="t(method === 'DIAGNOSIS' ? 'ranch.diagnosisResults.type64' : 'ranch.diagnosisResults.birthStyle')">
    <p v-if="!page.data.some(result => result.method === method)">{{ t('ranch.diagnosisResults.notCompleted') }}</p>
    <ul v-else class="space-y-2"><li v-for="result in page.data.filter(item => item.method === method)" :key="result.id"><NuxtLink :to="`/my/ranch/results/${result.id}`" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosisResults.completedAt', { date: result.completedAt }) }}</NuxtLink></li></ul>
    <NuxtLink :to="method === 'DIAGNOSIS' ? '/my/ranch/diagnosis' : '/my/ranch/birth-profile'" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosisResults.retake') }}</NuxtLink>
   </SectionCard>
   <Button v-if="page.meta.hasNext && page.meta.nextCursor" class="min-h-11" :label="t('ranch.next')" @click="load(page.meta.nextCursor!)" />
  </template>
 </div>
</template>
