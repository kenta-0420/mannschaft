<script setup lang="ts">
const props = defineProps<{
  teamId: string
  slotId: number | null
  lineId: number | null
  lineName: string
  date: string
  startTime: string
  endTime: string
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  reserved: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()

const submitting = ref(false)
const serviceNotes = ref('')

async function submit() {
  if (!props.slotId || !props.lineId) return
  submitting.value = true
  try {
    await reservationApi.createReservation(props.teamId, {
      reservationSlotId: props.slotId,
      lineId: props.lineId,
      userNote: serviceNotes.value.trim() || undefined,
    })
    notification.success(t('reservation.message.reserve_success'))
    emit('reserved')
    close()
  }
  catch { notification.error(t('reservation.message.reserve_failed')) }
  finally { submitting.value = false }
}

function close() {
  emit('update:visible', false)
  serviceNotes.value = ''
}
</script>

<template>
  <Dialog :visible="visible" :header="t('reservation.dialog.reserve_confirm')" :style="{ width: '400px' }" modal @update:visible="close">
    <div class="space-y-4">
      <div class="rounded-lg bg-surface-50 p-4 dark:bg-surface-700/50">
        <div class="space-y-2 text-sm">
          <div class="flex justify-between"><span class="text-surface-500">{{ t('reservation.field.line') }}</span><span class="font-medium">{{ lineName }}</span></div>
          <div class="flex justify-between"><span class="text-surface-500">{{ t('reservation.field.date') }}</span><span class="font-medium">{{ date }}</span></div>
          <div class="flex justify-between"><span class="text-surface-500">{{ t('reservation.field.time') }}</span><span class="font-medium">{{ startTime }} - {{ endTime }}</span></div>
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.note') }}</label>
        <Textarea v-model="serviceNotes" rows="2" class="w-full" :placeholder="t('reservation.placeholder.note')" />
      </div>
    </div>
    <template #footer>
      <Button :label="t('reservation.button.cancel')" text @click="close" />
      <Button :label="t('reservation.button.reserve')" icon="pi pi-check" :loading="submitting" @click="submit" />
    </template>
  </Dialog>
</template>
