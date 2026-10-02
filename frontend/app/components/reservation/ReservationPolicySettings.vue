<script setup lang="ts">
/**
 * チーム予約ポリシー編集UI（ADMIN限定）
 *
 * - 承認モード（AUTO/MANUAL）のセレクタ
 * - キャンセル期限（cancelDeadlineHours）の数値入力
 * - リマインドタイミング（remindBeforeHours）のCSV文字列入力
 * - 仮押さえ(PENDING)自動失効（pendingExpireHours・1〜168時間）の数値入力＋
 *   「自動キャンセルしない」トグル（clearPendingExpireHours。F03.4.5 §6.4 W2-6-FE）
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
/** 仮押さえ(PENDING)自動失効までの時間数（1〜168・W2-6）。無効化中も再有効化時の初期値として保持する */
const pendingExpireHours = ref<number>(24)
/** true = clearPendingExpireHours（自動失効しない）。BE から pendingExpireHours が null/undefined で返る状態と対応 */
const pendingExpireDisabled = ref<boolean>(false)

/** リマインド入力の検証エラー */
const remindValidationError = ref<string>('')
/** 仮押さえ自動失効・時間数入力の検証エラー */
const pendingExpireValidationError = ref<string>('')

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
    // BE: pendingExpireHours が null/undefined = clearPendingExpireHours 済み（自動失効しない）。
    // 無効化中も数値入力欄には再有効化時の初期値として直近の値（無ければ既定24）を表示する。
    pendingExpireDisabled.value = s.pendingExpireHours == null
    pendingExpireHours.value = s.pendingExpireHours ?? 24
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

// --- 仮押さえ(PENDING)自動失効（W2-6・§6.4）---

/** 1〜168（1時間〜7日）の範囲チェック。BE 400 に頼り切らず FE でも弾く。 */
function validatePendingExpireHours(value: number): boolean {
  pendingExpireValidationError.value = ''
  if (!Number.isInteger(value) || value < 1 || value > 168) {
    pendingExpireValidationError.value = t('reservation.settings.policy.pending_expire.error_range')
    return false
  }
  return true
}

async function savePendingExpireHours() {
  if (pendingExpireDisabled.value) return
  if (!validatePendingExpireHours(pendingExpireHours.value)) return
  saving.value = true
  try {
    await reservationApi.updateReservationSettings(props.teamId, {
      pendingExpireHours: pendingExpireHours.value,
    })
    notification.success(t('reservation.settings.policy.save_success'))
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    saving.value = false
  }
}

/**
 * 「自動キャンセルしない」トグル。ON（disabled=true）は clearPendingExpireHours:true を送る
 * （BE Javadoc: clearPendingExpireHours と pendingExpireHours を同時指定した場合は clear が優先されるため
 * pendingExpireHours は同時送信しない）。OFF（再有効化）は直近の入力値（無効なら既定24）を pendingExpireHours
 * として送る。保存失敗時はトグル表示をロールバックしてサーバー状態とのズレを防ぐ。
 */
async function onTogglePendingExpireDisabled(next: boolean) {
  saving.value = true
  try {
    if (next) {
      await reservationApi.updateReservationSettings(props.teamId, { clearPendingExpireHours: true })
      pendingExpireDisabled.value = true
    }
    else {
      const hours = Number.isInteger(pendingExpireHours.value)
        && pendingExpireHours.value >= 1 && pendingExpireHours.value <= 168
        ? pendingExpireHours.value
        : 24
      pendingExpireHours.value = hours
      await reservationApi.updateReservationSettings(props.teamId, { pendingExpireHours: hours })
      pendingExpireDisabled.value = false
    }
    notification.success(t('reservation.settings.policy.save_success'))
  }
  catch (error) {
    // ロールバック: 保存失敗時に UI とサーバー状態がズレるのを防ぐ
    pendingExpireDisabled.value = !next
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

      <!-- 仮押さえ(PENDING)自動失効（W2-6・§6.4） -->
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.settings.policy.pending_expire.label') }}
        </label>
        <p class="mb-2 text-xs text-surface-500">
          {{ t('reservation.settings.policy.pending_expire.hint') }}
        </p>
        <div class="mb-2 flex items-center gap-2">
          <Checkbox
            v-model="pendingExpireDisabled"
            :binary="true"
            input-id="pending-expire-disable-toggle"
            :disabled="disabled || saving"
            @update:model-value="onTogglePendingExpireDisabled"
          />
          <label for="pending-expire-disable-toggle" class="cursor-pointer text-sm">
            {{ t('reservation.settings.policy.pending_expire.no_expire_toggle') }}
          </label>
        </div>
        <div class="flex items-center gap-2">
          <InputNumber
            v-model="pendingExpireHours"
            :min="1"
            :max="168"
            :disabled="disabled || saving || pendingExpireDisabled"
            show-buttons
            button-layout="horizontal"
            class="w-40"
            :suffix="t('reservation.settings.policy.pending_expire.unit')"
          />
          <Button
            :label="t('reservation.button.save')"
            size="small"
            :disabled="disabled || saving || pendingExpireDisabled"
            :loading="saving"
            @click="savePendingExpireHours"
          />
        </div>
        <p v-if="pendingExpireValidationError" class="mt-1 text-xs text-red-500">
          {{ pendingExpireValidationError }}
        </p>
        <!-- 承認制（MANUAL）かつ自動失効なしの場合のみ注意書き（自動承認チームは仮押さえが発生しないため無意味）-->
        <Message
          v-if="pendingExpireDisabled && approvalMode === 'MANUAL'"
          severity="warn"
          :closable="false"
          class="mt-2"
        >
          {{ t('reservation.settings.policy.pending_expire.no_expire_warning') }}
        </Message>
      </div>
    </template>
  </div>
</template>
