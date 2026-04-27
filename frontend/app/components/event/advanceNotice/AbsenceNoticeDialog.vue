<script setup lang="ts">
import type {
  AbsenceNoticeRequest,
  AdvanceAbsenceReason,
  AdvanceNoticeResponse,
} from '~/types/care'

/**
 * F03.12 §15 事前欠席連絡ダイアログ。
 *
 * <p>本人または見守り者（保護者）が「欠席」を理由 ENUM つきで申告する。</p>
 *
 * <p>BE {@code AbsenceNoticeRequest} は {@code AbsenceReason} の {@code NOT_ARRIVED}
 * を Pattern 制約で除外しているため、フロント側でも {@link AdvanceAbsenceReason}
 * （SICK / PERSONAL_REASON / OTHER）のみを選択肢として提示する。</p>
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
  submitted: [response: AdvanceNoticeResponse | null]
}>()

const { t } = useI18n()
const teamIdRef = computed(() => props.teamId)
const eventIdRef = computed(() => props.eventId)
const { submitAbsence } = useAdvanceNotice(teamIdRef, eventIdRef)
const { success, info, error: notifyError } = useNotification()

const COMMENT_MAX = 500
const REASON_OPTIONS: { value: AdvanceAbsenceReason; labelKey: string }[] = [
  { value: 'SICK', labelKey: 'event.advanceNotice.absenceReason.SICK' },
  { value: 'PERSONAL_REASON', labelKey: 'event.advanceNotice.absenceReason.PERSONAL_REASON' },
  { value: 'OTHER', labelKey: 'event.advanceNotice.absenceReason.OTHER' },
]

const absenceReason = ref<AdvanceAbsenceReason>('SICK')
const comment = ref<string>('')
const submitting = ref(false)

const commentValid = computed(() => (comment.value?.length ?? 0) <= COMMENT_MAX)
const canSubmit = computed(() => commentValid.value)

watch(
  () => props.open,
  (visible) => {
    if (visible) {
      absenceReason.value = 'SICK'
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
    const body: AbsenceNoticeRequest = {
      userId: props.userId,
      absenceReason: absenceReason.value,
      comment: comment.value.trim() ? comment.value.trim() : undefined,
    }
    const result = await submitAbsence(body)
    if (result === null) {
      info(t('event.advanceNotice.offlineQueued'))
    } else {
      success(t('event.advanceNotice.absenceNoticeSubmitted'))
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
    :header="t('event.advanceNotice.absenceDialogTitle')"
    :style="{ width: '420px' }"
    modal
    :data-testid="'absence-notice-dialog'"
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <!-- 理由 -->
      <div>
        <label class="mb-2 block text-sm font-medium">{{ t('event.advanceNotice.absenceReasonLabel') }}</label>
        <div class="flex flex-col gap-2">
          <label
            v-for="opt in REASON_OPTIONS"
            :key="opt.value"
            class="flex min-h-[44px] cursor-pointer items-center gap-2 rounded-lg border border-surface-200 px-3 py-2 hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800"
          >
            <RadioButton
              v-model="absenceReason"
              :value="opt.value"
              :input-id="`absence-reason-${opt.value}`"
              :data-testid="`absence-reason-${opt.value}`"
            />
            <span class="text-sm">{{ t(opt.labelKey) }}</span>
          </label>
        </div>
      </div>

      <!-- コメント -->
      <div>
        <label
          for="absence-notice-comment"
          class="mb-1 block text-sm font-medium"
        >{{ t('event.advanceNotice.commentOptional') }}</label>
        <Textarea
          id="absence-notice-comment"
          v-model="comment"
          rows="3"
          class="w-full"
          :maxlength="COMMENT_MAX"
          data-testid="absence-notice-comment-input"
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
        data-testid="absence-notice-cancel"
        @click="close"
      />
      <Button
        :label="t('event.advanceNotice.submitButton')"
        icon="pi pi-send"
        :loading="submitting"
        :disabled="!canSubmit || submitting"
        data-testid="absence-notice-submit"
        @click="handleSubmit"
      />
    </template>
  </Dialog>
</template>
