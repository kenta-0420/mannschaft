<script setup lang="ts">
const props = defineProps<{
  visible: boolean
  periodText: string
  subject: string
  cancelReservations: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'confirm': []
}>()

const visibleModel = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    :header="$t('emergency_closure.dialog.title')"
    :style="{ width: '420px' }"
    modal
  >
    <div class="space-y-2 text-sm">
      <p>{{ $t('emergency_closure.dialog.confirm_message') }}</p>
      <ul class="mt-2 space-y-1 rounded-md bg-surface-50 p-3 dark:bg-surface-800">
        <li><span class="font-medium">{{ $t('emergency_closure.dialog.label_period') }}:</span> {{ periodText }}</li>
        <li><span class="font-medium">{{ $t('emergency_closure.dialog.label_subject') }}:</span> {{ subject }}</li>
        <li v-if="cancelReservations" class="text-orange-600 dark:text-orange-400">
          {{ $t('emergency_closure.dialog.cancel_warning') }}
        </li>
      </ul>
    </div>
    <template #footer>
      <Button :label="$t('button.cancel')" text @click="visibleModel = false" />
      <Button :label="$t('emergency_closure.button.send_confirm')" icon="pi pi-send" severity="danger" @click="emit('confirm')" />
    </template>
  </Dialog>
</template>
