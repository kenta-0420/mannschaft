<script setup lang="ts">
const props = defineProps<{
  visible: boolean
  isOptOut: boolean
  teamId: number
}>()

const emit = defineEmits<{
  'update:visible': [val: boolean]
  confirmed: []
}>()

const { t: $t } = useI18n()
const { optOut, optIn } = useEquipmentTrending(props.teamId)
const { showSuccess, showError } = useNotification()
const loading = ref(false)

async function handleConfirm() {
  loading.value = true
  try {
    if (props.isOptOut) {
      await optIn()
      showSuccess($t('equipment.trending.opt_in_success'))
    } else {
      await optOut()
      showSuccess($t('equipment.trending.opt_out_success'))
    }
    emit('confirmed')
    emit('update:visible', false)
  } catch {
    showError($t('equipment.trending.opt_out_error'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="isOptOut ? $t('equipment.trending.opt_in_title') : $t('equipment.trending.opt_out_title')"
    modal
    :style="{ width: '360px' }"
    @update:visible="emit('update:visible', $event)"
  >
    <p class="text-sm text-surface-600 leading-relaxed">
      {{ isOptOut ? $t('equipment.trending.opt_in_message') : $t('equipment.trending.opt_out_message') }}
    </p>
    <template #footer>
      <Button
        :label="$t('common.button.cancel')"
        text
        @click="emit('update:visible', false)"
      />
      <Button
        :label="isOptOut ? $t('equipment.trending.opt_in_confirm') : $t('equipment.trending.opt_out_confirm')"
        :severity="isOptOut ? 'primary' : 'danger'"
        :loading="loading"
        @click="handleConfirm"
      />
    </template>
  </Dialog>
</template>
