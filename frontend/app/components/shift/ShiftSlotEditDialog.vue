<script setup lang="ts">
import type {
  ShiftSlotResponse,
  CreateShiftSlotRequest,
  UpdateShiftSlotRequest,
  ShiftPositionResponse,
} from '~/types/shift'

const props = defineProps<{
  visible: boolean
  scheduleId: number
  slot?: ShiftSlotResponse | null
  positions: ShiftPositionResponse[]
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: [slot: ShiftSlotResponse]
}>()

const { t } = useI18n()
const { createSlot, updateSlot } = useShiftSlotApi()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()

const saving = ref(false)

interface FormState {
  slotDate: string
  startTime: string
  endTime: string
  positionId: number | null
  requiredCount: number
  note: string
}

const form = ref<FormState>({
  slotDate: '',
  startTime: '09:00',
  endTime: '17:00',
  positionId: null,
  requiredCount: 1,
  note: '',
})

watch(
  () => props.visible,
  (visible) => {
    if (!visible) return
    if (props.slot) {
      form.value = {
        slotDate: props.slot.slotDate,
        startTime: props.slot.startTime,
        endTime: props.slot.endTime,
        positionId: props.slot.positionId,
        requiredCount: props.slot.requiredCount,
        note: props.slot.note ?? '',
      }
    } else {
      form.value = {
        slotDate: '',
        startTime: '09:00',
        endTime: '17:00',
        positionId: null,
        requiredCount: 1,
        note: '',
      }
    }
  },
  { immediate: true },
)

async function save() {
  if (!form.value.slotDate) return
  saving.value = true
  try {
    let saved: ShiftSlotResponse
    if (props.slot) {
      const payload: UpdateShiftSlotRequest = {
        slotDate: form.value.slotDate,
        startTime: form.value.startTime,
        endTime: form.value.endTime,
        positionId: form.value.positionId ?? undefined,
        requiredCount: form.value.requiredCount,
        note: form.value.note.trim() || undefined,
      }
      saved = await updateSlot(props.slot.id, payload)
    } else {
      const payload: CreateShiftSlotRequest = {
        slotDate: form.value.slotDate,
        startTime: form.value.startTime,
        endTime: form.value.endTime,
        positionId: form.value.positionId ?? undefined,
        requiredCount: form.value.requiredCount,
        note: form.value.note.trim() || undefined,
      }
      saved = await createSlot(props.scheduleId, payload)
    }
    success(t('shift.slot.saveSuccess'))
    emit('saved', saved)
    close()
  } catch (error) {
    handleApiError(error)
  } finally {
    saving.value = false
  }
}

function close() {
  emit('update:visible', false)
}

const positionOptions = computed(() => [
  { label: t('shift.slot.noPosition'), value: null },
  ...props.positions.map((p) => ({ label: p.name, value: p.id })),
])

const dialogHeader = computed(() =>
  props.slot ? t('shift.slot.editTitle') : t('shift.slot.createTitle'),
)
</script>

<template>
  <Dialog
    :visible="visible"
    :header="dialogHeader"
    :style="{ width: '480px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <!-- 日付 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.date') }}</label>
        <InputText v-model="form.slotDate" type="date" class="w-full" />
      </div>

      <!-- 時間帯 -->
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.startTime') }}</label>
          <InputText v-model="form.startTime" type="time" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.endTime') }}</label>
          <InputText v-model="form.endTime" type="time" class="w-full" />
        </div>
      </div>

      <!-- ポジション -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.position') }}</label>
        <Select
          v-model="form.positionId"
          :options="positionOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>

      <!-- 必要人数 -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.requiredCount') }}</label>
        <InputNumber v-model="form.requiredCount" :min="1" :max="99" class="w-full" />
      </div>

      <!-- メモ -->
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.slot.note') }}</label>
        <InputText v-model="form.note" class="w-full" :placeholder="t('shift.slot.notePlaceholder')" />
      </div>
    </div>

    <template #footer>
      <Button :label="t('common.cancel')" text @click="close" />
      <Button
        :label="t('common.save')"
        icon="pi pi-check"
        :loading="saving"
        :disabled="!form.slotDate"
        @click="save"
      />
    </template>
  </Dialog>
</template>
