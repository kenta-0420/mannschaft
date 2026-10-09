<script setup lang="ts">
import type { DiagnosisSession } from '~/types/ranch'
definePageMeta({ middleware: 'auth' })
const { t, locale } = useI18n(); useHead({ title: t('ranch.diagnosisResults.type64') })
const api = useDiagnosisApi(); const route = useRoute(); const { handleApiError } = useErrorHandler()
const session = ref<DiagnosisSession | null>(null); const answers = ref<Record<string,number>>({}); const ties = ref<Record<string,number>>({})
const loading = ref(false); const failed = ref(false); const saved = ref(false)
const tieNotice = ref<HTMLElement | null>(null)
const allAnswered = computed(() => {
 const current = session.value
 return current?.questions.length === 24 && current.questions.every(question => {
  const answer = answers.value[question.id]
  return answer !== undefined && answer >= 1 && answer <= 5
 })
})
function apply(value: DiagnosisSession) { session.value = value; answers.value = Object.fromEntries(value.answers.map(a => [a.questionId,a.value])); ties.value = {}; saved.value = true }
async function load() {
 loading.value = true; failed.value = false
 try {
  const value = typeof route.query.session === 'string' ? await api.session(route.query.session) : await api.pendingSession()
  if (!api.isCurrent()) return
  if (value) apply(value)
  else { session.value = null; answers.value = {}; ties.value = {}; saved.value = false }
 } catch(error) {
  if (!api.isCurrent()) return
  failed.value = true; handleApiError(error, 'DiagnosisLoad')
 } finally { if (api.isCurrent()) loading.value = false }
}
async function run(action: () => Promise<DiagnosisSession>) {
 loading.value = true; failed.value = false
 try {
  const value = await action()
  if (!api.isCurrent()) return
  apply(value)
  await navigateTo({ path: '/my/ranch/diagnosis', query: { session: value.id } })
  if (value.status === 'COMPLETED' && value.resultId) await navigateTo(`/my/ranch/results/${value.resultId}`)
  return value
 } catch(error) {
  if (!api.isCurrent()) return
  failed.value = true; handleApiError(error, 'DiagnosisCommand')
  if ((error as {statusCode?:number;status?:number}).statusCode === 409 || (error as {status?:number}).status === 409) await load()
 } finally { if (api.isCurrent()) loading.value = false }
}
function save() { if (session.value) { const current = session.value; return run(() => api.save(current, Object.entries(answers.value).map(([questionId,value]) => ({ questionId,value })))) } }
async function focusTieNotice() {
 await nextTick()
 tieNotice.value?.focus({ preventScroll: true })
 tieNotice.value?.scrollIntoView({ behavior: 'instant', block: 'center' })
}
async function complete() {
 if (!session.value || !allAnswered.value || loading.value || api.command.running.value || api.command.pending.value) return
 // 保存失敗や応答喪失・409後の再読込を、保存成功として扱わない。
 const current = saved.value ? session.value : await save()
 if (!current || !allAnswered.value || api.command.pending.value) return
 // サーバーが返した追加質問は、すべて選んでから完了を送る。
 if (current.tieQuestions.some(question => ties.value[question.axisId] === undefined)) {
  await focusTieNotice()
  return
 }
 const completed = await run(() => api.complete(current, Object.entries(ties.value).map(([axisId,value]) => ({axisId,value}))))
 // 最初の完了応答で同点が判明した場合も、選択案内を見える位置へ移す。
 if (completed?.tieQuestions.some(question => ties.value[question.axisId] === undefined)) await focusTieNotice()
 return completed
}
watch(answers, () => { saved.value = false; ties.value = {} }, { deep: true, flush: 'sync' })
async function retryPending() { await run(() => api.retryPending<DiagnosisSession>()) }
async function hold() { if (!saved.value) await save(); if (saved.value && !api.command.pending.value && session.value) await navigateTo({ path: '/my/ranch/results', query: { session: session.value.id } }) }
// 本人が切り替わる瞬間に既表示の私的回答も消し、旧scopeで操作させない。
const auth = useAuthStore()
watch(() => auth.user?.id ?? null, () => {
 session.value = null; answers.value = {}; ties.value = {}; saved.value = false
 failed.value = false; loading.value = true
}, { flush: 'sync' })
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
   <p v-if="saved && session.tieQuestions.length" ref="tieNotice" role="status" tabindex="-1">{{ t('ranch.diagnosis.tie') }}</p>
   <SectionCard v-for="question in saved ? session.tieQuestions : []" :key="question.axisId" :title="t('ranch.diagnosis.tie')">
    <label v-for="value in [0,1]" :key="value" class="flex min-h-11 items-center gap-2"><RadioButton v-model="ties[question.axisId]" :name="question.axisId" :value="value" :disabled="!!api.command.pending.value" />{{ (value === 0 ? question.zero : question.one)[locale] ?? (value === 0 ? question.zero : question.one).ja }}</label>
   </SectionCard>
   <div v-if="session.status !== 'COMPLETED' && session.status !== 'CANCELLED'" class="flex flex-wrap gap-3">
    <Button class="min-h-11" :label="t('ranch.diagnosis.save')" :disabled="api.command.running.value || !!api.command.pending.value" @click="save" />
    <Button class="min-h-11" :label="t('ranch.diagnosis.complete')" :disabled="api.command.running.value || !!api.command.pending.value || !allAnswered || (saved && session.tieQuestions.some(q => ties[q.axisId] === undefined))" @click="complete" />
    <Button class="min-h-11" :label="t('ranch.diagnosis.hold')" outlined :disabled="!!api.command.pending.value" @click="hold" />
    <Button class="min-h-11" :label="t('ranch.diagnosis.cancel')" outlined :disabled="!!api.command.pending.value" @click="run(() => api.cancel(session!))" />
   </div>
  </template>
 </div>
</template>
