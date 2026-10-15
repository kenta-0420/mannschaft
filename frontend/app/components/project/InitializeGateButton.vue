<script setup lang="ts">
defineProps<{
  disabled?: boolean
  loading?: boolean
}>()

const emit = defineEmits<{
  confirm: []
}>()

const showConfirm = ref(false)

function onConfirm() {
  showConfirm.value = false
  emit('confirm')
}
</script>

<template>
  <div>
    <Button
      :label="$t('project.initialize_gate_button')"
      icon="pi pi-refresh"
      severity="info"
      outlined
      :disabled="disabled"
      :loading="loading"
      data-testid="initialize-gate-button"
      @click="showConfirm = true"
    />

    <Dialog
      v-model:visible="showConfirm"
      :header="$t('project.initialize_gate_title')"
      :modal="true"
      class="w-96"
      data-testid="initialize-gate-dialog"
    >
      <p class="text-sm">{{ $t('project.initialize_gate_description') }}</p>
      <template #footer>
        <Button
          :label="$t('button.cancel')"
          severity="secondary"
          text
          @click="showConfirm = false"
        />
        <Button
          :label="$t('project.initialize_gate_submit')"
          severity="info"
          data-testid="initialize-gate-submit"
          @click="onConfirm"
        />
      </template>
    </Dialog>
  </div>
</template>
