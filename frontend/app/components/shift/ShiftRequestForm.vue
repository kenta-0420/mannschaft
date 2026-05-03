<script setup lang="ts">
import type { ShiftPreference } from '~/types/shift'
const props = defineProps<{
  teamId: number
  scheduleId: number
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  submitted: []
}>()

const shiftApi = useShiftApi()
const notification = useNotification()

const submitting = ref(false)
const form = ref({
  slotDate: null as Date | null,
  preference: 'WANT' as ShiftPreference,
  note: '',
})

const preferenceOptions = [
  { label: '出勤希望', value: 'WANT', icon: 'pi pi-check', color: 'text-green-600' },
  { label: '出勤不可', value: 'DONT_WANT', icon: 'pi pi-times', color: 'text-red-600' },
  { label: 'どちらでも', value: 'NEUTRAL', icon: 'pi pi-minus', color: 'text-surface-500' },
]

async function submit() {
  if (!form.value.slotDate) return
  submitting.value = true
  try {
    const slotDate = form.value.slotDate.toISOString().split('T')[0] ?? ''
    await shiftApi.submitShiftRequest({
      scheduleId: props.scheduleId,
      slotDate,
      preference: form.value.preference,
      note: form.value.note.trim() || undefined,
    })
    notification.success('シフト希望を提出しました')
    emit('submitted')
    close()
  } catch {
    notification.error('提出に失敗しました')
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:visible', false)
  form.value = { slotDate: null, preference: 'WANT' as ShiftPreference, note: '' }
}
</script>

<template>
  <Dialog
    :visible="visible"
    header="シフト希望を提出"
    :style="{ width: '420px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">日付</label>
        <DatePicker v-model="form.slotDate" date-format="yy/mm/dd" class="w-full" show-icon />
      </div>
      <div>
        <label class="mb-2 block text-sm font-medium">希望</label>
        <div class="flex gap-2">
          <button
            v-for="opt in preferenceOptions"
            :key="opt.value"
            class="flex flex-1 items-center justify-center gap-2 rounded-lg border-2 p-2 text-sm transition-all"
            :class="
              form.preference === opt.value
                ? 'border-primary bg-primary/5'
                : 'border-surface-200 dark:border-surface-600'
            "
            @click="form.preference = opt.value as ShiftPreference"
          >
            <i :class="[opt.icon, opt.color]" />
            {{ opt.label }}
          </button>
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">メモ（任意）</label>
        <InputText v-model="form.note" class="w-full" placeholder="理由など" />
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button label="提出" icon="pi pi-check" :loading="submitting" @click="submit" />
    </template>
  </Dialog>
</template>
