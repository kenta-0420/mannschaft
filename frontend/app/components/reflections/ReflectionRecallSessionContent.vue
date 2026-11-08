<!-- 本人・entry・sessionの外側keyでscopeを再生成する。 -->
<script setup lang="ts">
import type { RecallSelfRating } from '~/types/reflection'
import type { RecallSessionAnswer, RecallSessionPrompt, RecallSessionView, useRecallSessionApi } from '~/composables/useRecallSessionApi'
const props = defineProps<{ entryId: string; sessionId?: string; api: ReturnType<typeof useRecallSessionApi> }>()
const emit = defineEmits<{ session: [id: string] }>()
const { t } = useI18n()
const session = ref<RecallSessionView | null>(null)
const draft = ref<{ prompt: RecallSessionPrompt; state: 'ANSWERED' | 'FORGOT' | null; text: string }[]>([])
const rating = ref<RecallSelfRating | null>(null)
const loading = ref(false)
const failed = ref(false)
let run = 0
let disposed = false
function apply(value: RecallSessionView) {
 session.value = value
 draft.value = value.prompts.map(prompt => {
  const saved = value.answers.find(answer => answer.promptId === prompt.id)
  return { prompt, state: saved?.state ?? null, text: saved?.text ?? '' }
 })
}
const terminal = computed(() => session.value?.status === 'COMPLETED' || session.value?.status === 'CANCELLED')
const uncertain = computed(() => !!props.api.command.pending.value)
const valid = computed(() => draft.value.length > 0 && draft.value.every(row => row.state === 'FORGOT' || (row.state === 'ANSWERED' && row.text.trim().length > 0 && row.text.length <= row.prompt.maxAnswerLength)))
function answers(partial: boolean): RecallSessionAnswer[] {
 const values: RecallSessionAnswer[] = []
 for (const row of draft.value) {
  if (row.state === 'FORGOT') values.push({ promptId: row.prompt.id, state: 'FORGOT', text: null })
  else if (row.state === 'ANSWERED' && row.text.trim().length > 0 && row.text.length <= row.prompt.maxAnswerLength) values.push({ promptId: row.prompt.id, state: 'ANSWERED', text: row.text })
  else if (!partial) throw new Error('RECALL_ANSWERS_INCOMPLETE')
 }
 return values
}
const canSave = computed(() => answers(true).length > 0)
async function perform(action: () => Promise<RecallSessionView>) {
 const identity = ++run; const entryId = props.entryId; const sessionId = props.sessionId
 const current = () => !disposed && identity === run && props.api.isCurrent() && props.entryId === entryId && props.sessionId === sessionId
 loading.value = true; failed.value = false
 try {
  const value = await action()
  if (!current()) return
  apply(value)
  emit('session', value.id)
 } catch { if (current()) failed.value = true }
 finally { if (current()) loading.value = false }
}
async function save() {
 const current = session.value
 if (current && canSave.value) await perform(() => props.api.save(current, answers(true)))
}
async function complete() {
 const current = session.value; const selectedRating = rating.value
 if (current && selectedRating && valid.value) await perform(() => props.api.complete(current, answers(false), selectedRating))
}
function reload() { const id = props.sessionId; if (id) return perform(() => props.api.session(id)) }
function cancel() { const current = session.value; if (current) return perform(() => props.api.cancel(current)) }
onMounted(() => { const id = props.sessionId; if (id) void perform(() => props.api.session(id)) })
onScopeDispose(() => { disposed = true; run += 1 })
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('reflection.arSession.title')" />
  <p>{{ t('reflection.arSession.description') }}</p>
  <PageLoading v-if="loading" />
  <p v-if="failed" role="alert">{{ t('reflection.arSession.failed') }}</p>
  <Button v-if="failed && props.sessionId && !uncertain && !loading" :label="t('reflection.arSession.retry')" @click="reload" />
  <Button v-if="uncertain && !loading" :label="t('reflection.arSession.retry')" @click="perform(api.retry)" />
  <Button v-if="!session && !props.sessionId && !loading && !uncertain" :label="t('reflection.arSession.start')" @click="perform(() => api.start(entryId))" />
  <template v-if="session && !loading">
   <template v-if="!terminal">
    <SectionCard v-for="row in draft" :key="row.prompt.id" :title="row.prompt.kind === 'FREE_RECALL' ? t('reflection.arSession.freeRecall') : (row.prompt.heading ?? t('reflection.arSession.card'))">
     <p v-if="row.prompt.promptText" class="whitespace-pre-wrap">{{ row.prompt.promptText }}</p>
     <p v-if="row.prompt.promptSide">{{ t(`reflection.arSession.side.${row.prompt.promptSide}`) }}</p>
     <label class="flex items-center gap-2 min-h-11"><RadioButton v-model="row.state" :name="row.prompt.id" value="ANSWERED" :disabled="uncertain" />{{ t('reflection.arSession.answered') }}</label>
     <label class="flex items-center gap-2 min-h-11"><RadioButton v-model="row.state" :name="row.prompt.id" value="FORGOT" :disabled="uncertain" />{{ t('reflection.arSession.forgot') }}</label>
     <label v-if="row.state === 'ANSWERED'" :for="`recall-answer-${row.prompt.id}`">{{ t('reflection.arSession.answer') }}</label>
     <Textarea v-if="row.state === 'ANSWERED'" :id="`recall-answer-${row.prompt.id}`" v-model="row.text" :maxlength="row.prompt.maxAnswerLength" :disabled="uncertain" class="w-full" />
    </SectionCard>
    <fieldset :disabled="uncertain" class="space-y-2">
     <legend>{{ t('reflection.arSession.selfRating') }}</legend>
     <label v-for="value in (['REMEMBERED','PARTIAL','FORGOT'] as const)" :key="value" class="flex items-center gap-2 min-h-11"><RadioButton v-model="rating" name="recall-self-rating" :value="value" />{{ t(`reflection.arSession.rating.${value}`) }}</label>
    </fieldset>
    <p>{{ t('reflection.arSession.ratingNeutral') }}</p>
    <div class="flex flex-wrap gap-3">
     <Button :label="t('reflection.arSession.save')" :disabled="uncertain || !canSave" @click="save" />
     <Button :label="t('reflection.arSession.complete')" :disabled="uncertain || !valid || !rating" @click="complete" />
     <Button :label="t('reflection.arSession.cancel')" outlined :disabled="uncertain" @click="cancel" />
    </div>
   </template>
   <p v-else-if="session.status === 'CANCELLED'">{{ t('reflection.arSession.cancelled') }}</p>
   <!-- 保存開始版のみ。通常entry GETや現在版原文を別fetchして開示しない。 -->
   <section v-else-if="session.status === 'COMPLETED' && session.original" :aria-label="t('reflection.arSession.original')">
    <h2>{{ t('reflection.arSession.original') }}</h2>
    <slot name="original" :entry="session.original" />
   </section>
  </template>
 </div>
</template>