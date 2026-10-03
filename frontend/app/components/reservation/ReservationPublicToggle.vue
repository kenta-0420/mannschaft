<script setup lang="ts">
/**
 * PUBLIC予約許可トグル（ADMIN限定・#1604 予約認可ゲートFE対応）
 *
 * - ADMIN のみ操作可能（props.disabled で制御）
 * - 既定 OFF（allowPublicReservation=false）
 * - 誤操作防止のため変更時に確認ダイアログを表示
 * - 変更成功時にトースト通知、失敗時はエラー表示（エラー握りつぶし禁止）
 */
const props = defineProps<{
  teamId: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 設定変更成功時（新しい allowPublicReservation 値）*/
  changed: [value: boolean]
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const allowPublic = ref(false)
const loading = ref(false)
const submitting = ref(false)
/** 確認ダイアログ表示フラグ（誤操作防止）*/
const showConfirmDialog = ref(false)
/** 確認ダイアログで確認待ちの新しい値 */
const pendingValue = ref(false)

async function loadSettings() {
  loading.value = true
  try {
    const res = await reservationApi.getReservationSettings(props.teamId)
    allowPublic.value = res.data.allowPublicReservation ?? false
  }
  catch {
    // 取得失敗: fallback を false に保持（安全方向）
    allowPublic.value = false
  }
  finally {
    loading.value = false
  }
}

/** トグル変更時: 確認ダイアログを表示してから実行 */
function onToggleChange(next: boolean) {
  // ToggleSwitch は v-model で即変更されるため、一度 UI を元に戻して確認を待つ
  allowPublic.value = !next
  pendingValue.value = next
  showConfirmDialog.value = true
}

async function confirmChange() {
  showConfirmDialog.value = false
  submitting.value = true
  const next = pendingValue.value
  try {
    await reservationApi.updateReservationSettings(props.teamId, { allowPublicReservation: next })
    allowPublic.value = next
    notification.success(t('reservation.settings.allow_public.save_success'))
    emit('changed', next)
  }
  catch (error) {
    // ロールバックせず（allowPublic は既に元の値のまま）
    handleApiError(error)
  }
  finally {
    submitting.value = false
  }
}

function cancelChange() {
  showConfirmDialog.value = false
  // pendingValue は適用しない（allowPublic は変更前の値のまま）
}

onMounted(loadSettings)
</script>

<template>
  <div>
    <div v-if="loading">
      <Skeleton height="2rem" width="16rem" />
    </div>
    <div v-else class="space-y-2">
      <div class="flex items-start gap-3">
        <ToggleSwitch
          v-model="allowPublic"
          :disabled="disabled || submitting"
          @update:model-value="onToggleChange"
        />
        <div class="flex-1">
          <p class="text-sm font-medium">
            {{ t('reservation.settings.allow_public.label') }}
          </p>
          <p class="mt-0.5 text-xs text-surface-500">
            {{ allowPublic
              ? t('reservation.settings.allow_public.hint_on')
              : t('reservation.settings.allow_public.hint_off') }}
          </p>
        </div>
      </div>
    </div>

    <!-- 誤操作防止確認ダイアログ -->
    <Dialog
      v-model:visible="showConfirmDialog"
      :header="t('reservation.settings.allow_public.confirm_title')"
      :style="{ width: '420px' }"
      modal
    >
      <p class="text-sm">
        {{ pendingValue
          ? t('reservation.settings.allow_public.confirm_enable')
          : t('reservation.settings.allow_public.confirm_disable') }}
      </p>
      <template #footer>
        <Button
          :label="t('reservation.button.cancel')"
          text
          @click="cancelChange"
        />
        <Button
          :label="t('reservation.settings.allow_public.confirm_ok')"
          :severity="pendingValue ? 'warn' : 'secondary'"
          icon="pi pi-check"
          :loading="submitting"
          @click="confirmChange"
        />
      </template>
    </Dialog>
  </div>
</template>
