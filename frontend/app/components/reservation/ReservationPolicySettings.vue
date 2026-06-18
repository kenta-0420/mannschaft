<script setup lang="ts">
/**
 * チーム予約ポリシー編集UI（ADMIN限定）
 *
 * - 承認モード（AUTO/MANUAL）のセレクタ
 * - キャンセル期限（cancelDeadlineHours）の数値入力
 * - リマインドタイミング（remindBeforeHours）のCSV文字列入力
 * - 変更成功時にトースト通知、失敗時はエラー表示（エラー握りつぶし禁止）
 * - PATCH は部分更新（変更フィールドのみ送信）
 */
const props = defineProps<{
  teamId: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 設定変更成功時（更新後の全設定）*/
  changed: [approvalMode: 'AUTO' | 'MANUAL', cancelDeadlineHours: number, remindBeforeHours: string]
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

// --- 状態管理 ---
const loading = ref(false)
const saving = ref(false)

/** 承認モード（AUTO=自動確定 / MANUAL=承認制）*/
const approvalMode = ref<'AUTO' | 'MANUAL'>('AUTO')
/** キャンセル受付締切（時間）*/
const cancelDeadlineHours = ref<number>(24)
/** リマインドタイミング CSV 文字列（例: "24,1"）*/
const remindBeforeHoursRaw = ref<string>('24')

/** リマインド入力の検証エラー */
const remindValidationError = ref<string>('')

/** 承認モード選択肢 */
const approvalModeOptions = computed(() => [
  { label: t('reservation.settings.policy.approval_mode.option_auto'), value: 'AUTO' as const },
  { label: t('reservation.settings.policy.approval_mode.option_manual'), value: 'MANUAL' as const },
])

// --- データ取得 ---
async function loadSettings() {
  loading.value = true
  try {
    const res = await reservationApi.getReservationSettings(props.teamId)
    const s = res.data
    approvalMode.value = s.approvalMode ?? 'AUTO'
    cancelDeadlineHours.value = s.cancelDeadlineHours ?? 24
    remindBeforeHoursRaw.value = s.remindBeforeHours ?? '24'
  }
  catch {
    // 取得失敗: デフォルト値を維持（エラーは表示しない – 初回ロードの404等は想定内）
  }
  finally {
    loading.value = false
  }
}

// --- バリデーション ---
function validateRemind(value: string): boolean {
  remindValidationError.value = ''
  if (!value.trim()) {
    remindValidationError.value = t('reservation.settings.policy.remind.error_empty')
    return false
  }
  const parts = value.split(',').map(v => v.trim())
  for (const part of parts) {
    const n = Number(part)
    if (!Number.isInteger(n) || n < 0 || n > 8760) {
      remindValidationError.value = t('reservation.settings.policy.remind.error_invalid')
      return false
    }
  }
  return true
}

// --- 保存処理（フィールド個別）---

async function saveApprovalMode(next: 'AUTO' | 'MANUAL') {
  saving.value = true
  try {
    await reservationApi.updateReservationSettings(props.teamId, { approvalMode: next })
    approvalMode.value = next
    notification.success(t('reservation.settings.policy.save_success'))
    emit('changed', next, cancelDeadlineHours.value, remindBeforeHoursRaw.value)
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    saving.value = false
  }
}

async function saveCancelDeadline() {
  if (cancelDeadlineHours.value < 0 || cancelDeadlineHours.value > 8760) return
  saving.value = true
  try {
    await reservationApi.updateReservationSettings(props.teamId, {
      cancelDeadlineHours: cancelDeadlineHours.value,
    })
    notification.success(t('reservation.settings.policy.save_success'))
    emit('changed', approvalMode.value, cancelDeadlineHours.value, remindBeforeHoursRaw.value)
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    saving.value = false
  }
}

async function saveRemind() {
  if (!validateRemind(remindBeforeHoursRaw.value)) return
  saving.value = true
  try {
    await reservationApi.updateReservationSettings(props.teamId, {
      remindBeforeHours: remindBeforeHoursRaw.value.trim(),
    })
    notification.success(t('reservation.settings.policy.save_success'))
    emit('changed', approvalMode.value, cancelDeadlineHours.value, remindBeforeHoursRaw.value)
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    saving.value = false
  }
}

onMounted(loadSettings)
</script>

<template>
  <div class="space-y-5">
    <!-- ローディング -->
    <div v-if="loading" class="space-y-3">
      <Skeleton height="2.5rem" width="100%" />
      <Skeleton height="2.5rem" width="100%" />
      <Skeleton height="2.5rem" width="100%" />
    </div>

    <template v-else>
      <!-- 承認モード -->
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.settings.policy.approval_mode.label') }}
        </label>
        <p class="mb-2 text-xs text-surface-500">
          {{ t('reservation.settings.policy.approval_mode.hint') }}
        </p>
        <div class="flex flex-wrap gap-3">
          <div
            v-for="opt in approvalModeOptions"
            :key="opt.value"
            class="flex items-center gap-2"
          >
            <RadioButton
              v-model="approvalMode"
              :input-id="`approval-${opt.value}`"
              :value="opt.value"
              :disabled="disabled || saving"
              @update:model-value="saveApprovalMode"
            />
            <label :for="`approval-${opt.value}`" class="cursor-pointer text-sm">
              {{ opt.label }}
            </label>
          </div>
        </div>
      </div>

      <!-- キャンセル期限 -->
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.settings.policy.cancel_deadline.label') }}
        </label>
        <p class="mb-2 text-xs text-surface-500">
          {{ t('reservation.settings.policy.cancel_deadline.hint') }}
        </p>
        <div class="flex items-center gap-2">
          <InputNumber
            v-model="cancelDeadlineHours"
            :min="0"
            :max="8760"
            :disabled="disabled || saving"
            show-buttons
            button-layout="horizontal"
            class="w-36"
            :suffix="t('reservation.settings.policy.cancel_deadline.unit')"
          />
          <Button
            :label="t('reservation.button.save')"
            size="small"
            :disabled="disabled || saving"
            :loading="saving"
            @click="saveCancelDeadline"
          />
        </div>
      </div>

      <!-- リマインドタイミング -->
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.settings.policy.remind.label') }}
        </label>
        <p class="mb-2 text-xs text-surface-500">
          {{ t('reservation.settings.policy.remind.hint') }}
        </p>
        <div class="flex items-center gap-2">
          <InputText
            v-model="remindBeforeHoursRaw"
            :disabled="disabled || saving"
            :placeholder="t('reservation.settings.policy.remind.placeholder')"
            class="w-40"
          />
          <Button
            :label="t('reservation.button.save')"
            size="small"
            :disabled="disabled || saving"
            :loading="saving"
            @click="saveRemind"
          />
        </div>
        <p v-if="remindValidationError" class="mt-1 text-xs text-red-500">
          {{ remindValidationError }}
        </p>
      </div>
    </template>
  </div>
</template>
