<script setup lang="ts">
const props = defineProps<{
  teamId: number
  slotId: number | null
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

const reservationApi = useReservationApi()
const notification = useNotification()

const submitting = ref(false)
const serviceNotes = ref('')

async function submit() {
  if (!props.slotId) return
  submitting.value = true
  try {
    await reservationApi.createReservation(props.teamId, {
      slotId: props.slotId,
      serviceNotes: serviceNotes.value.trim() || undefined,
    })
    notification.success('予約が完了しました')
    emit('reserved')
    close()
  }
  catch { notification.error('予約に失敗しました') }
  finally { submitting.value = false }
}

function close() {
  emit('update:visible', false)
  serviceNotes.value = ''
}
</script>

<template>
  <Dialog :visible="visible" header="予約確認" :style="{ width: '400px' }" modal @update:visible="close">
    <div class="space-y-4">
      <div class="rounded-lg bg-surface-50 p-4 dark:bg-surface-700/50">
        <div class="space-y-2 text-sm">
          <div class="flex justify-between"><span class="text-surface-500">ライン</span><span class="font-medium">{{ lineName }}</span></div>
          <div class="flex justify-between"><span class="text-surface-500">日付</span><span class="font-medium">{{ date }}</span></div>
          <div class="flex justify-between"><span class="text-surface-500">時間</span><span class="font-medium">{{ startTime }} - {{ endTime }}</span></div>
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">備考（任意）</label>
        <Textarea v-model="serviceNotes" rows="2" class="w-full" placeholder="メニュー・症状など" />
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button label="予約する" icon="pi pi-check" :loading="submitting" @click="submit" />
    </template>
  </Dialog>
</template>
