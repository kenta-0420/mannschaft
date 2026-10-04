<script setup lang="ts">
import dayjs from 'dayjs'
import type { ShiftPreference } from '~/types/shift'
import ShiftPreferenceRadioCard from './ShiftPreferenceRadioCard.vue'
const props = defineProps<{
  scheduleId: number
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  submitted: []
}>()

const shiftApi = useShiftApi()
const notification = useNotification()
const { userTimezone } = useDatetime()
const { t } = useI18n()

const submitting = ref(false)
const form = ref({
  slotDate: null as Date | null,
  preference: 'PREFERRED' as ShiftPreference,
  note: '',
})

async function submit() {
  if (!form.value.slotDate) return
  submitting.value = true
  try {
    const slotDate = dayjs(form.value.slotDate).tz(userTimezone.value).format('YYYY-MM-DD')
    await shiftApi.submitShiftRequest({
      scheduleId: props.scheduleId,
      slotDate,
      preference: form.value.preference,
      note: form.value.note.trim() || undefined,
    })
    notification.success(t('shift.notification.submitSuccess'))
    emit('submitted')
    close()
  } catch {
    notification.error(t('shift.notification.errorSubmit'))
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:visible', false)
  form.value = { slotDate: null, preference: 'PREFERRED' as ShiftPreference, note: '' }
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="t('shift.page.submitRequest')"
    :style="{ width: '420px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.field.slotDate') }}</label>
        <DatePicker
          v-model="form.slotDate"
          date-format="yy/mm/dd"
          class="w-full"
          input-class="min-h-11 text-base"
          show-icon
        />
      </div>
      <div>
        <label class="mb-2 block text-sm font-medium">{{ t('shift.field.preference') }}</label>
        <ShiftPreferenceRadioCard v-model="form.preference" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('shift.field.note') }}</label>
        <InputText
          v-model="form.note"
          class="min-h-11 w-full text-base"
          :placeholder="t('shift.slot.notePlaceholder')"
        />
      </div>
    </div>
    <template #footer>
      <Button :label="t('common.cancel')" class="min-h-11 min-w-11" text @click="close" />
      <Button
        :label="t('shift.action.submit')"
        class="min-h-11 min-w-11"
        icon="pi pi-check"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
