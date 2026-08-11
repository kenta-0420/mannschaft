<script setup lang="ts">
/**
 * F03.16 予定コメント — 投稿・返信フォーム。
 *
 * トップレベル投稿・返信の両方に使う（`parentId` を渡すと返信モード）。
 * メンションは「@」以降の文字列で `mention-candidates` を検索し、選択すると
 * 本文に `@表示名 ` を挿入しつつ `mentionedUserIds` に userId を積む
 * （ロケール値に生の `@` を含めない・§8.1 の注記どおりコンポーネント側で描画する）。
 */
import Button from 'primevue/button'
import Textarea from 'primevue/textarea'
import type { ScheduleCommentMentionCandidate } from '~/types/scheduleComment'

const props = defineProps<{
  scheduleId: number
  parentId?: string | null
  replyToName?: string | null
  submitting: boolean
  autofocus?: boolean
}>()

const emit = defineEmits<{
  submit: [body: string, mentionedUserIds: number[]]
  cancel: []
}>()

const { t } = useI18n()
const { mentionCandidates } = useScheduleComments()

const body = ref('')
const selectedMentions = ref<ScheduleCommentMentionCandidate[]>([])
const mentionQuery = ref<string | null>(null)
const mentionResults = ref<ScheduleCommentMentionCandidate[]>([])
const mentionLoading = ref(false)

const MAX_LEN = 2000
const MAX_MENTIONS = 20

const tooLong = computed(() => body.value.length > MAX_LEN)
const canSubmit = computed(
  () => body.value.trim().length > 0 && !tooLong.value && !props.submitting,
)

// テキストエリアの末尾が「@断片」の形かどうかで簡易メンション検知を行う。
// カーソル位置追跡までは行わず、常に末尾のトークンを対象にする（一般的なチャット系メンションUIの簡易実装）。
watch(body, (val) => {
  const match = /(?:^|\s)@([^\s@]*)$/.exec(val)
  if (match) {
    mentionQuery.value = match[1]
  } else {
    mentionQuery.value = null
    mentionResults.value = []
  }
})

let mentionDebounce: ReturnType<typeof setTimeout> | null = null
watch(mentionQuery, (q) => {
  if (q === null) return
  if (mentionDebounce) clearTimeout(mentionDebounce)
  mentionDebounce = setTimeout(async () => {
    mentionLoading.value = true
    try {
      const res = await mentionCandidates(props.scheduleId, q || undefined, 20)
      mentionResults.value = res.data
    } catch {
      mentionResults.value = []
    } finally {
      mentionLoading.value = false
    }
  }, 250)
})

function pickMention(candidate: ScheduleCommentMentionCandidate) {
  if (!candidate.userId) return
  body.value = body.value.replace(/(?:^|\s)@([^\s@]*)$/, (m) =>
    (m.startsWith(' ') ? ' ' : '') + `@${candidate.displayName} `,
  )
  if (!selectedMentions.value.some((c) => c.userId === candidate.userId)) {
    if (selectedMentions.value.length < MAX_MENTIONS) {
      selectedMentions.value.push(candidate)
    }
  }
  mentionQuery.value = null
  mentionResults.value = []
}

function submit() {
  if (!canSubmit.value) return
  const mentionedUserIds = selectedMentions.value
    .map((c) => c.userId)
    .filter((id): id is number => id !== undefined)
    .slice(0, MAX_MENTIONS)
  emit('submit', body.value.trim(), mentionedUserIds)
  body.value = ''
  selectedMentions.value = []
  mentionQuery.value = null
  mentionResults.value = []
}

function cancel() {
  body.value = ''
  selectedMentions.value = []
  emit('cancel')
}

defineExpose({ reset: () => { body.value = ''; selectedMentions.value = [] } })
</script>

<template>
  <div class="flex flex-col gap-2" data-testid="schedule-comment-form">
    <div v-if="replyToName" class="text-xs text-surface-500">
      {{ t('schedule.comment.replyTo', { name: replyToName }) }}
    </div>
    <div class="relative">
      <Textarea
        v-model="body"
        rows="2"
        class="w-full"
        :autofocus="autofocus"
        :placeholder="t('schedule.comment.placeholder')"
        data-testid="schedule-comment-input"
      />
      <ul
        v-if="mentionQuery !== null && (mentionResults.length > 0 || mentionLoading)"
        class="absolute z-10 mt-1 max-h-48 w-full overflow-y-auto rounded border border-surface-200 bg-white shadow-md dark:border-surface-600 dark:bg-surface-800"
        data-testid="schedule-comment-mention-list"
      >
        <li v-if="mentionLoading" class="px-3 py-2 text-xs text-surface-400">
          <i class="pi pi-spin pi-spinner" />
        </li>
        <li
          v-for="c in mentionResults"
          :key="c.userId"
          class="flex cursor-pointer items-center gap-2 px-3 py-2 text-sm hover:bg-surface-100 dark:hover:bg-surface-700"
          @mousedown.prevent="pickMention(c)"
        >
          <Avatar
            :image="c.avatarUrl ?? undefined"
            :label="c.avatarUrl ? undefined : (c.displayName?.charAt(0) ?? '?')"
            shape="circle"
            size="normal"
          />
          <span>{{ c.displayName }}</span>
        </li>
        <li
          v-if="!mentionLoading && mentionResults.length === 0"
          class="px-3 py-2 text-xs text-surface-400"
        >
          {{ t('schedule.comment.mention.empty') }}
        </li>
      </ul>
    </div>
    <div class="flex items-center justify-between">
      <span
        v-if="tooLong"
        class="text-xs text-red-500"
        data-testid="schedule-comment-error-too-long"
      >
        {{ t('schedule.comment.error.bodyTooLong') }}
      </span>
      <span v-else class="text-xs text-surface-400">{{ t('schedule.comment.mention.hint') }}</span>
      <div class="flex gap-2">
        <Button
          v-if="parentId"
          :label="t('schedule.comment.cancel')"
          size="small"
          text
          @click="cancel"
        />
        <Button
          :label="t('schedule.comment.submit')"
          icon="pi pi-send"
          size="small"
          severity="primary"
          :disabled="!canSubmit"
          :loading="submitting"
          data-testid="schedule-comment-submit"
          @click="submit"
        />
      </div>
    </div>
  </div>
</template>
