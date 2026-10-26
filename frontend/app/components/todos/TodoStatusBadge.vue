<script setup lang="ts">
defineProps<{
  status: string
}>()

const config: Record<string, { label: string; severity: string; icon: string }> = {
  OPEN: { label: '未着手', severity: 'secondary', icon: 'pi pi-circle' },
  IN_PROGRESS: { label: '進行中', severity: 'info', icon: 'pi pi-spin pi-spinner' },
  COMPLETED: { label: '完了', severity: 'success', icon: 'pi pi-check-circle' },
}
</script>

<template>
  <Tag
    :value="config[status]?.label ?? status"
    :severity="config[status]?.severity ?? 'secondary'"
    :icon="config[status]?.icon"
    rounded
  />
</template>
