<script setup lang="ts">
import type { ShiftScheduleResponse } from '~/types/shift'

const props = defineProps<{
  schedule: ShiftScheduleResponse
  canManage: boolean
}>()

const { t } = useI18n()
const shiftApi = useShiftApi()
const notification = useNotification()
const confirm = useConfirm()
const reminding = ref(false)

const visible = computed(() => props.canManage && props.schedule.status.status === 'COLLECTING')

function remindUnsubmitted() {
  if (reminding.value) return

  confirm.require({
    message: t('shift.reminder.confirmMessage', { title: props.schedule.content.title }),
    header: t('shift.reminder.confirmTitle'),
    icon: 'pi pi-bell',
    acceptLabel: t('shift.reminder.confirmAccept'),
    rejectLabel: t('shift.reminder.confirmReject'),
    accept: async () => {
      reminding.value = true
      try {
        const response = await shiftApi.remindUnsubmitted(props.schedule.id)
        if (response.remindedCount === 0) {
          notification.info(t('shift.reminder.noRecipientsTitle'), t('shift.reminder.noRecipientsDetail'))
        } else {
          notification.success(
            t('shift.reminder.sentTitle'),
            t('shift.reminder.sentDetail', { count: response.remindedCount }),
          )
        }
      } catch {
        notification.error(t('shift.reminder.failedTitle'), t('shift.reminder.failedDetail'))
      } finally {
        reminding.value = false
      }
    },
  })
}
</script>

<template>
  <Button
    v-if="visible"
    :label="t('shift.reminder.send')"
    icon="pi pi-bell"
    size="small"
    severity="secondary"
    outlined
    :loading="reminding"
    :disabled="reminding"
    :data-testid="`shift-reminder-${schedule.id}`"
    @click.stop="remindUnsubmitted"
  />
</template>
