<script setup lang="ts">
import type { ReservationSlotResponse } from '~/types/reservation'

/**
 * 予約枠（Slot）作成・編集ダイアログ。
 * 承認モード（チーム設定に従う / AUTO / MANUAL）を3択セレクタで操作できる。
 *
 * 送信仕様:
 *   作成時: 「チーム設定に従う」→ approvalMode を省略。AUTO/MANUAL → その値を送る。
 *   編集時: 「チーム設定に従う」→ clearApprovalMode: true を送る。AUTO/MANUAL → approvalMode: その値。
 */
const props = defineProps<{
  visible: boolean
  teamId: string
  /** 編集時のみ渡す。null = 作成モード */
  editingSlot?: ReservationSlotResponse | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()

// ─── 承認モードの選択肢 ──────────────────────────────────────────────
type ApprovalModeOption = 'INHERIT' | 'AUTO' | 'MANUAL'

interface ApprovalModeItem {
  value: ApprovalModeOption
  label: string
}

const approvalModeOptions = computed<ApprovalModeItem[]>(() => [
  { value: 'INHERIT', label: t('reservation.slot_form.approval_mode.option_inherit') },
  { value: 'AUTO', label: t('reservation.slot_form.approval_mode.option_auto') },
  { value: 'MANUAL', label: t('reservation.slot_form.approval_mode.option_manual') },
])

// ─── フォーム状態 ────────────────────────────────────────────────────
interface SlotForm {
  slotDate: string
  startTime: string
  endTime: string
  title: string
  note: string
  approvalMode: ApprovalModeOption
}

const form = ref<SlotForm>({
  slotDate: '',
  startTime: '',
  endTime: '',
  title: '',
  note: '',
  approvalMode: 'INHERIT',
})

const saving = ref(false)

const isEdit = computed(() => !!props.editingSlot)

// ─── ダイアログ開閉同期 ──────────────────────────────────────────────
const dialogVisible = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v),
})

// 編集対象が変わったらフォームを初期化
watch(
  () => props.editingSlot,
  (slot) => {
    if (slot) {
      // 編集モード: レスポンスの policy.approvalMode を初期値に
      const serverMode = slot.policy?.approvalMode
      form.value = {
        slotDate: slot.basic?.slotDate ?? '',
        startTime: (slot.basic?.startTime ?? '').substring(0, 5), // HH:mm に丸める
        endTime: (slot.basic?.endTime ?? '').substring(0, 5),
        title: slot.basic?.title ?? '',
        note: slot.status?.note ?? '',
        approvalMode: (serverMode === 'AUTO' || serverMode === 'MANUAL') ? serverMode : 'INHERIT',
      }
    }
    else {
      // 作成モード: 初期値リセット
      form.value = {
        slotDate: '',
        startTime: '',
        endTime: '',
        title: '',
        note: '',
        approvalMode: 'INHERIT',
      }
    }
  },
  { immediate: true },
)

// ─── 保存処理 ────────────────────────────────────────────────────────
async function save() {
  if (!form.value.slotDate || !form.value.startTime || !form.value.endTime) return

  saving.value = true
  try {
    if (isEdit.value && props.editingSlot?.id != null) {
      // 編集: clearApprovalMode か approvalMode を送る
      const body =
        form.value.approvalMode === 'INHERIT'
          ? {
              slotDate: form.value.slotDate,
              startTime: `${form.value.startTime}:00`,
              endTime: `${form.value.endTime}:00`,
              title: form.value.title || undefined,
              note: form.value.note || undefined,
              clearApprovalMode: true,
            }
          : {
              slotDate: form.value.slotDate,
              startTime: `${form.value.startTime}:00`,
              endTime: `${form.value.endTime}:00`,
              title: form.value.title || undefined,
              note: form.value.note || undefined,
              approvalMode: form.value.approvalMode,
            }

      await reservationApi.updateSlot(props.teamId, props.editingSlot.id, body)
      notification.success(t('reservation.slot_form.message.update_success'))
    }
    else {
      // 作成: INHERIT なら approvalMode を省略
      const body =
        form.value.approvalMode === 'INHERIT'
          ? {
              slotDate: form.value.slotDate,
              startTime: `${form.value.startTime}:00`,
              endTime: `${form.value.endTime}:00`,
              title: form.value.title || undefined,
              note: form.value.note || undefined,
            }
          : {
              slotDate: form.value.slotDate,
              startTime: `${form.value.startTime}:00`,
              endTime: `${form.value.endTime}:00`,
              title: form.value.title || undefined,
              note: form.value.note || undefined,
              approvalMode: form.value.approvalMode,
            }

      await reservationApi.createSlot(props.teamId, body)
      notification.success(t('reservation.slot_form.message.create_success'))
    }

    dialogVisible.value = false
    emit('saved')
  }
  catch {
    notification.error(t('reservation.slot_form.message.save_failed'))
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="dialogVisible"
    :header="isEdit ? t('reservation.slot_form.dialog.edit_title') : t('reservation.slot_form.dialog.create_title')"
    :style="{ width: '420px' }"
    modal
  >
    <div class="flex flex-col gap-4">
      <!-- 日付 -->
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('reservation.slot_form.field.date') }} <span class="text-red-500">*</span>
        </label>
        <InputText
          v-model="form.slotDate"
          type="date"
          class="w-full"
          :placeholder="t('reservation.slot_form.placeholder.date')"
        />
      </div>

      <!-- 開始・終了時刻 -->
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.slot_form.field.start_time') }} <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="form.startTime"
            type="time"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.slot_form.field.end_time') }} <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="form.endTime"
            type="time"
            class="w-full"
          />
        </div>
      </div>

      <!-- タイトル（任意） -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.slot_form.field.title') }}</label>
        <InputText
          v-model="form.title"
          class="w-full"
          :placeholder="t('reservation.slot_form.placeholder.title')"
        />
      </div>

      <!-- メモ（任意） -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.slot_form.field.note') }}</label>
        <InputText
          v-model="form.note"
          class="w-full"
          :placeholder="t('reservation.slot_form.placeholder.note')"
        />
      </div>

      <!-- 承認モードセレクタ -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.slot_form.approval_mode.label') }}</label>
        <Select
          v-model="form.approvalMode"
          :options="approvalModeOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
        <p class="mt-1 text-xs text-surface-500">{{ t('reservation.slot_form.approval_mode.hint') }}</p>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('reservation.button.cancel')"
        severity="secondary"
        text
        @click="dialogVisible = false"
      />
      <Button
        :label="t('reservation.button.save')"
        :loading="saving"
        :disabled="!form.slotDate || !form.startTime || !form.endTime"
        @click="save"
      />
    </template>
  </Dialog>
</template>
