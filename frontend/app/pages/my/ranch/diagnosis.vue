<script setup lang="ts">
import type { DiagnosisSession } from '~/types/ranch'
definePageMeta({ middleware: 'auth' })
const { t, locale } = useI18n(); useHead({ title: t('ranch.diagnosisResults.type64') })
const api = useDiagnosisApi(); const route = useRoute(); const { handleApiError } = useErrorHandler()
const session = ref<DiagnosisSession | null>(null); const answers = ref<Record<string,number>>({}); const ties = ref<Record<string,number>>({})
const loading = ref(false); const failed = ref(false); const saved = ref(false)
const allAnswered = computed(() => session.value?.questions.length === 24 && session.value.questions.every(q => answers.value[q.id] >= 1 && answers.value[q.id] <= 5))
function apply(value: DiagnosisSession) { session.value = value; answers.value = Object.fromEntries(value.answers.map(a => [a.questionId,a.value])); ties.value = {}; saved.value = true }
async function load() { loading.value = true; failed.value = false; try { if (typeof route.query.session === 'string') apply(await api.session(route.query.session)) } catch(error) { failed.value = true; handleApiError(error, 'DiagnosisLoad') } finally { loading.value = false } }
async function run(action: () => Promise<DiagnosisSession>) { loading.value = true; failed.value = false; try { const value = await action(); apply(value); await navigateTo({ path: '/my/ranch/diagnosis', query: { session: value.id } }); if (value.status === 'COMPLETED' && value.resultId) await navigateTo(`/my/ranch/results/${value.resultId}`) } catch(error) { failed.value = true; handleApiError(error, 'DiagnosisCommand'); if ((error as {statusCode?:number;status?:number}).statusCode === 409 || (error as {status?:number}).status === 409) await load() } finally { loading.value = false } }
function save() { if (session.value) { const current = session.value; return run(() => api.save(current, Object.entries(answers.value).map(([questionId,value]) => ({ questionId,value })))) } }
function complete() { if (session.value) { const current = session.value; return run(() => api.complete(current, Object.entries(ties.value).map(([axisId,value]) => ({axisId,value})))) } }
watch(answers, () => { saved.value = false; ties.value = {} }, { deep: true, flush: 'sync' })
async function retryPending() { await run(() => api.retryPending<DiagnosisSession>()) }
async function hold() { if (!saved.value) await save(); if (saved.value && !api.command.pending.value && session.value) await navigateTo({ path: '/my/ranch/results', query: { session: session.value.id } }) }
onMounted(load)
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.diagnosisResults.type64')" back-to="/my/ranch/results" />
  <p>{{ t('ranch.diagnosis.description') }}</p><p>{{ t('ranch.diagnosisResults.avatarUnchanged') }}</p>
  <Button v-if="api.command.pending.value && !loading" class="min-h-11" :label="t('ranch.retry')" @click="retryPending" />
  <PageLoading v-if="loading" />
  <DashboardErrorState v-if="failed" @retry="load" />
  <Button v-if="!session && !loading && !failed" class="min-h-11" :label="t('ranch.diagnosis.start')" @click="run(api.start)" />
  <template v-if="session && !loading">
   <SectionCard v-for="question in session.questions" :key="question.id" :title="question.text[locale] ?? question.text.ja">
    <div class="flex flex-wrap gap-3">
     <label v-for="value in 5" :key="value" class="flex min-h-11 min-w-11 items-center gap-2"><RadioButton v-model="answers[question.id]" :name="question.id" :value="value" :disabled="session.status === 'COMPLETED' || session.status === 'CANCELLED' || !!api.command.pending.value" />{{ t(`ranch.diagnosis.scale${value}`) }}</label>
    </div>
   </SectionCard>
   <SectionCard v-for="question in saved ? session.tieQuestions : []" :key="question.axisId" :title="t('ranch.diagnosis.tie')">
    <label v-for="value in [0,1]" :key="value" class="flex min-h-11 items-center gap-2"><RadioButton v-model="ties[question.axisId]" :name="question.axisId" :value="value" :disabled="!!api.command.pending.value" />{{ (value === 0 ? question.zero : question.one)[locale] ?? (value === 0 ? question.zero : question.one).ja }}</label>
   </SectionCard>
   <div v-if="session.status !== 'COMPLETED' && session.status !== 'CANCELLED'" class="flex flex-wrap gap-3">
    <Button class="min-h-11" :label="t('ranch.diagnosis.save')" :disabled="api.command.running.value || !!api.command.pending.value" @click="save" />
    <Button class="min-h-11" :label="t('ranch.diagnosis.complete')" :disabled="!!api.command.pending.value || !allAnswered || !saved || session.tieQuestions.some(q => ties[q.axisId] === undefined)" @click="complete" />
    <Button class="min-h-11" :label="t('ranch.diagnosis.hold')" outlined :disabled="!!api.command.pending.value" @click="hold" />
    <Button class="min-h-11" :label="t('ranch.diagnosis.cancel')" outlined :disabled="!!api.command.pending.value" @click="run(() => api.cancel(session!))" />
   </div>
  </template>
 </div>
</template>
