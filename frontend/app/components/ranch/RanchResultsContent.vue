<script setup lang="ts">
// 方式ごとのGET成功を確認してから、未診断・最新結果・履歴を表示する。
import type { DiagnosisResult, DiagnosisSession } from '~/types/ranch'
const { t } = useI18n(); useHead({ title: t('ranch.diagnosisResults.title') })
const api = useDiagnosisApi(); const route = useRoute(); const { handleApiError } = useErrorHandler()
const { formatDateTime } = useDatetime()
const methods = ['DIAGNOSIS', 'BIRTH_STYLE'] as const
type Method = typeof methods[number]
interface MethodResults { results: DiagnosisResult[]; loading: boolean; failed: boolean; received: boolean; cursor: string | null; run: number }
const groups = reactive<Record<Method, MethodResults>>({
 DIAGNOSIS: { results: [], loading: false, failed: false, received: false, cursor: null, run: 0 },
 BIRTH_STYLE: { results: [], loading: false, failed: false, received: false, cursor: null, run: 0 },
})
const pendingSession = ref<DiagnosisSession | null>(null)
const pendingLoading = ref(true); const pendingFailed = ref(false)
async function loadPending() {
 pendingLoading.value = true; pendingFailed.value = false
 try {
  const value = typeof route.query.session === 'string' ? await api.session(route.query.session) : await api.pendingSession()
  if (!api.isCurrent()) return
  pendingSession.value = value && (value.status === 'STARTED' || value.status === 'TIE_BREAK_REQUIRED') ? value : null
 } catch (error) {
  if (!api.isCurrent()) return
  pendingFailed.value = true; handleApiError(error, 'DiagnosisPending')
 } finally { if (api.isCurrent()) pendingLoading.value = false }
}
async function load(method: Method, cursor?: string) {
 const group = groups[method]
 const run = ++group.run
 group.loading = true; group.failed = false
 if (!cursor) { group.results = []; group.cursor = null; group.received = false }
 try {
  const page = await api.results(method, cursor)
  if (!api.isCurrent() || group.run !== run) return
  group.received = true
  group.results = cursor ? [...group.results, ...page.data] : page.data
  group.cursor = page.meta.hasNext ? page.meta.nextCursor : null
 } catch (error) {
  if (!api.isCurrent() || group.run !== run) return
  group.failed = true; handleApiError(error, 'DiagnosisResults')
 } finally { if (api.isCurrent() && group.run === run) group.loading = false }
}
onMounted(() => { for (const method of methods) void load(method); void loadPending() })
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.diagnosisResults.title')" back-to="/my/ranch" />
  <p>{{ t('ranch.diagnosisResults.avatarUnchanged') }}</p>
  <PageLoading v-if="pendingLoading" />
  <DashboardErrorState v-else-if="pendingFailed" @retry="loadPending" />
  <NuxtLink v-else-if="pendingSession" :to="{ path: '/my/ranch/diagnosis', query: { session: pendingSession.id } }" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosis.resume') }}</NuxtLink>
  <SectionCard v-for="method in methods" :key="method" :title="t(method === 'DIAGNOSIS' ? 'ranch.diagnosisResults.type64' : 'ranch.diagnosisResults.birthStyle')">
   <PageLoading v-if="!groups[method].received && !groups[method].failed" />
   <DashboardErrorState v-else-if="groups[method].failed" @retry="load(method)" />
   <template v-else>
    <p v-if="groups[method].results.length === 0">{{ t('ranch.diagnosisResults.notCompleted') }}</p>
    <template v-else>
     <h3 class="font-medium">{{ t('ranch.diagnosisResults.latest') }}</h3>
     <NuxtLink v-for="result in groups[method].results.slice(0,1)" :key="result.id" :to="`/my/ranch/results/${result.id}`" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosisResults.completedAt', { date: formatDateTime(result.completedAt) }) }}</NuxtLink>
     <h3 v-if="groups[method].results.length > 1" class="font-medium mt-3">{{ t('ranch.diagnosisResults.history') }}</h3>
     <ul class="space-y-2"><li v-for="result in groups[method].results.slice(1)" :key="result.id"><NuxtLink :to="`/my/ranch/results/${result.id}`" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosisResults.completedAt', { date: formatDateTime(result.completedAt) }) }}</NuxtLink></li></ul>
    </template>
    <PageLoading v-if="groups[method].loading" />
    <Button v-if="groups[method].cursor" class="min-h-11" :label="t('ranch.next')" :disabled="groups[method].loading" @click="load(method, groups[method].cursor ?? undefined)" />
   </template>
   <NuxtLink :to="method === 'DIAGNOSIS' ? '/my/ranch/diagnosis' : '/my/ranch/birth-profile'" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosisResults.retake') }}</NuxtLink>
  </SectionCard>
 </div>
</template>
