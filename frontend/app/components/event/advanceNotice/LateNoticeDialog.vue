<script setup lang="ts">
import type { LateNoticeRequest } from '~/types/care'

/**
 * F03.12 §15 事前遅刻連絡ダイアログ。
 *
 * <p>本人または見守り者（保護者）がイベントに対して「N 分遅刻予定」を申告する。
 * 親コンポーネント（{@link LateAbsenceNoticeBar}）から open フラグと userId を受け取り、
 * 送信成功時に {@code submitted} を emit する。</p>
 */

const props = defineProps<{
  teamId: number
  eventId: number
  /** 申告対象の userId（本人 or ケア対象者）。 */
  userId: number
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  submitted: [response: import('~/types/care').AdvanceNoticeResponse | null]
}>()

const { t } = useI18n()
const teamIdRef = computed(() => props.teamId)
const eventIdRef = computed(() => props.eventId)
const { submitLate } = useAdvanceNotice(teamIdRef, eventIdRef)
const { success, info, error: notifyError } = useNotification()

const lateArrivalMinutes = ref<number>(15)
const comment = ref<string>('')
const submitting = ref(false)

const COMMENT_MAX = 500
const MIN_MINUTES = 1
const MAX_MINUTES = 120

const minutesValid = computed(
  () =>
    typeof lateArrivalMinutes.value === 'number' &&
    Number.isFinite(lateArrivalMinutes.value) &&
    lateArrivalMinutes.value >= MIN_MINUTES &&
    lateArrivalMinutes.value <= MAX_MINUTES,
)
const commentValid = computed(() => (comment.value?.length ?? 0) <= COMMENT_MAX)
const canSubmit = computed(() => minutesValid.value && commentValid.value)

watch(
  () => props.open,
  (visible) => {
    if (visible) {
      lateArrivalMinutes.value = 15
      comment.value = ''
    }
  },
)

function close() {
  emit('update:open', false)
}

async function handleSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const body: LateNoticeRequest = {
      userId: props.userId,
      expectedArrivalMinutesLate: lateArrivalMinutes.value,
      comment: comment.value.trim() ? comment.value.trim() : undefined,
    }
    const result = await submitLate(body)
    if (result === null) {
      info(t('event.advanceNotice.offlineQueued'))
    } else {
      success(t('event.advanceNotice.lateNoticeSubmitted'))
    }
    emit('submitted', result)
    close()
  } catch (e) {
    notifyError(t('event.advanceNotice.submitFailed'), e instanceof Error ? e.message : undefined)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="open"
    :header="t('event.advanceNotice.lateDialogTitle')"
    :style="{ width: '420px' }"
    modal
    :data-testid="'late-notice-dialog'"
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <!-- 遅刻分数 -->
      <div>
        <label
          for="late-notice-minutes"
          class="mb-1 block text-sm font-medium"
        >{{ t('event.advanceNotice.lateMinutes') }}</label>
        <InputNumber
          v-model="lateArrivalMinutes"
          input-id="late-notice-minutes"
          :min="MIN_MINUTES"
          :max="MAX_MINUTES"
          show-buttons
          class="w-full"
          :input-props="{ 'data-testid': 'late-notice-minutes-input' }"
        />
        <p
          v-if="!minutesValid"
          class="mt-1 text-xs text-red-600"
          data-testid="late-notice-minutes-error"
        >
          {{ t('event.advanceNotice.lateMinutesRangeError', { min: MIN_MINUTES, max: MAX_MINUTES }) }}
        </p>
      </div>

      <!-- コメント -->
      <div>
        <label
          for="late-notice-comment"
          class="mb-1 block text-sm font-medium"
        >{{ t('event.advanceNotice.commentOptional') }}</label>
        <Textarea
          id="late-notice-comment"
          v-model="comment"
          rows="3"
          class="w-full"
          :maxlength="COMMENT_MAX"
          data-testid="late-notice-comment-input"
        />
        <p class="mt-1 text-right text-xs text-surface-500">
          {{ comment.length }} / {{ COMMENT_MAX }}
        </p>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('common.cancel')"
        text
        severity="secondary"
        data-testid="late-notice-cancel"
        @click="close"
      />
      <Button
        :label="t('event.advanceNotice.submitButton')"
        icon="pi pi-send"
        :loading="submitting"
        :disabled="!canSubmit || submitting"
        data-testid="late-notice-submit"
        @click="handleSubmit"
      />
    </template>
  </Dialog>
</template>
