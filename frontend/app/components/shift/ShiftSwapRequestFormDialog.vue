<script setup lang="ts">
import type { CreateSwapRequestRequest, ShiftSlotResponse } from '~/types/shift'

/** 個別指名・同日シフト帯・全体募集の3モードで交代依頼を送信する。 */

type RecipientMode = 'SPECIFIC' | 'TEMPLATE' | 'OPEN_CALL'

const props = defineProps<{
  visible: boolean
  slotId: number
  slotDate: string
  scheduleId: number
  teamId: string
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'submitted'): void
}>()

const { t } = useI18n()
const { error: showError, success: showSuccess } = useNotification()
const { createSwapRequest } = useShiftSwapApi()
const { listSlots } = useShiftSlotApi()

// ---- ダイアログ表示制御 ----
const dialogVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => {
    if (!v) selectedUserIds.value = []
    emit('update:visible', v)
  },
})

// ---- フォーム状態 ----
const recipientMode = ref<RecipientMode>('SPECIFIC')
const reason = ref('')
const submitting = ref(false)

// ---- SPECIFIC モード ----
const selectedUserIds = ref<number[]>([])

// ---- TEMPLATE モード ----
const slots = ref<ShiftSlotResponse[]>([])
const slotsLoading = ref(false)
const templatePeriod = ref<'day' | 'night'>('day')

// ---- 初期化 ----
watch(
  () => [props.visible, props.teamId] as const,
  async ([visible]) => {
    selectedUserIds.value = []
    if (!visible) return
    recipientMode.value = 'SPECIFIC'
    reason.value = ''
    templatePeriod.value = 'day'
    await loadSlots()
  },
  { immediate: true, flush: 'sync' },
)

async function loadSlots() {
  if (slots.value.length > 0) return
  slotsLoading.value = true
  try {
    slots.value = await listSlots(props.scheduleId)
  } catch {
    // スロット取得失敗時は TEMPLATE モードが無効になる
  } finally {
    slotsLoading.value = false
  }
}

// ---- TEMPLATEモード: 同日の昼/夜スロット担当者を抽出 ----
function isDayShift(startTime: string): boolean {
  const hour = parseInt(startTime.substring(0, 2), 10)
  return hour < 12
}

const sameDateSlots = computed(() =>
  slots.value.filter((s) => s.time.slotDate === props.slotDate),
)

const dayShiftUserIds = computed((): number[] => {
  const ids = new Set<number>()
  for (const s of sameDateSlots.value) {
    if (isDayShift(s.time.startTime)) {
      s.assignedUserIds.forEach((id) => ids.add(id))
    }
  }
  return [...ids]
})

const nightShiftUserIds = computed((): number[] => {
  const ids = new Set<number>()
  for (const s of sameDateSlots.value) {
    if (!isDayShift(s.time.startTime)) {
      s.assignedUserIds.forEach((id) => ids.add(id))
    }
  }
  return [...ids]
})

const templateTargetUserIds = computed(() =>
  templatePeriod.value === 'day' ? dayShiftUserIds.value : nightShiftUserIds.value,
)

// ---- 送信 ----
async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const body: CreateSwapRequestRequest = {
      slotId: props.slotId,
      reason: reason.value || undefined,
    }
    if (recipientMode.value === 'OPEN_CALL') {
      body.openCall = true
    } else if (recipientMode.value === 'TEMPLATE') {
      body.openCall = false
      body.targetUserIds = templateTargetUserIds.value
    } else {
      body.openCall = false
      body.targetUserIds = selectedUserIds.value
    }

    await createSwapRequest(body)
    showSuccess(t('shift.notification.submitSuccess'))
    emit('submitted')
    dialogVisible.value = false
  } catch {
    showError(t('shift.notification.errorSubmit'))
  } finally {
    submitting.value = false
  }
}

function close() {
  dialogVisible.value = false
}

// ---- バリデーション ----
const canSubmit = computed(() => {
  if (recipientMode.value === 'SPECIFIC') {
    return selectedUserIds.value.length > 0
  }
  if (recipientMode.value === 'TEMPLATE') {
    return templateTargetUserIds.value.length > 0
  }
  return true // OPEN_CALL
})

// ---- i18n ヘルパー ----
const modeOptions: { value: RecipientMode; labelKey: string }[] = [
  { value: 'SPECIFIC', labelKey: 'shift.swap.recipientMode.specific' },
  { value: 'TEMPLATE', labelKey: 'shift.swap.recipientMode.template' },
  { value: 'OPEN_CALL', labelKey: 'shift.swap.recipientMode.openCall' },
]
</script>

<template>
  <Dialog
    v-model:visible="dialogVisible"
    :header="t('shift.swap.create')"
    modal
    class="w-full max-w-lg"
  >
    <div class="flex flex-col gap-5">
      <!-- モード選択 -->
      <div>
        <label class="mb-2 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('shift.swap.recipientMode.label') }}
        </label>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="opt in modeOptions"
            :key="opt.value"
            type="button"
            class="min-h-[44px] rounded-lg border px-4 py-2 text-sm font-medium transition-colors"
            :class="
              recipientMode === opt.value
                ? 'border-primary bg-primary text-white'
                : 'border-surface-200 bg-surface-0 text-surface-600 hover:bg-surface-50 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-300 dark:hover:bg-surface-800'
            "
            @click="recipientMode = opt.value"
          >
            {{ t(opt.labelKey) }}
          </button>
        </div>
      </div>

      <!-- SPECIFIC: 候補の取得と選択を専用部品で管理 -->
      <ShiftSwapMemberPicker
        v-if="recipientMode === 'SPECIFIC'"
        v-model="selectedUserIds"
        :visible="dialogVisible"
        :team-id="teamId"
      />

      <!-- TEMPLATE: 昼/夜選択 -->
      <div v-else-if="recipientMode === 'TEMPLATE'">
        <label class="mb-2 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('shift.swap.targetUsers.label') }}
        </label>
        <div v-if="slotsLoading" class="py-4 text-center text-sm text-surface-400">
          <i class="pi pi-spin pi-spinner" />
        </div>
        <div v-else class="flex flex-wrap gap-2">
          <button
            type="button"
            class="min-h-[44px] rounded-lg border px-4 py-2 text-sm font-medium transition-colors"
            :class="
              templatePeriod === 'day'
                ? 'border-primary bg-primary text-white'
                : 'border-surface-200 bg-surface-0 text-surface-600 hover:bg-surface-50 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-300 dark:hover:bg-surface-800'
            "
            @click="templatePeriod = 'day'"
          >
            <i class="pi pi-sun mr-1.5" />
            {{ t('shift.swap.targetUsers.dayShift') }}
            <span class="ml-1 text-xs opacity-70">({{ dayShiftUserIds.length }})</span>
          </button>
          <button
            type="button"
            class="min-h-[44px] rounded-lg border px-4 py-2 text-sm font-medium transition-colors"
            :class="
              templatePeriod === 'night'
                ? 'border-primary bg-primary text-white'
                : 'border-surface-200 bg-surface-0 text-surface-600 hover:bg-surface-50 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-300 dark:hover:bg-surface-800'
            "
            @click="templatePeriod = 'night'"
          >
            <i class="pi pi-moon mr-1.5" />
            {{ t('shift.swap.targetUsers.nightShift') }}
            <span class="ml-1 text-xs opacity-70">({{ nightShiftUserIds.length }})</span>
          </button>
        </div>
        <p class="mt-2 text-xs text-surface-500 dark:text-surface-400">
          {{
            t('shift.swap.templateHint', {
              period: templatePeriod === 'day'
                ? t('shift.swap.targetUsers.dayShift')
                : t('shift.swap.targetUsers.nightShift'),
            })
          }}
        </p>
        <div
          v-if="templateTargetUserIds.length === 0 && !slotsLoading"
          class="mt-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700 dark:border-amber-800 dark:bg-amber-900/30 dark:text-amber-300"
        >
          <i class="pi pi-exclamation-triangle mr-1" />
          {{ t('shift.empty.noShifts') }}
        </div>
      </div>

      <!-- OPEN_CALL: 説明文のみ -->
      <div v-else class="rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 dark:border-blue-800 dark:bg-blue-900/30">
        <div class="flex items-start gap-2">
          <i class="pi pi-megaphone mt-0.5 text-blue-600 dark:text-blue-400" />
          <p class="text-sm text-blue-700 dark:text-blue-300">
            {{ t('shift.swap.recipientMode.openCall') }}
          </p>
        </div>
      </div>

      <!-- 理由（任意） -->
      <div>
        <label class="mb-1 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('shift.field.reason') }}
          <span class="ml-1 text-xs text-surface-400">（{{ t('label.optional') }}）</span>
        </label>
        <Textarea
          v-model="reason"
          :placeholder="t('shift.field.reason')"
          rows="3"
          class="w-full"
        />
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button
          :label="t('button.cancel')"
          text
          severity="secondary"
          @click="close"
        />
        <Button
          :label="t('shift.action.submit')"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="submit"
        />
      </div>
    </template>
  </Dialog>
</template>
