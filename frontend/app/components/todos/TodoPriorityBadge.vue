<script setup lang="ts">
defineProps<{
  priority: string
}>()

const config: Record<string, { label: string; severity: string; icon: string }> = {
  LOW: { label: '低', severity: 'secondary', icon: 'pi pi-chevron-down' },
  MEDIUM: { label: '中', severity: 'info', icon: 'pi pi-minus' },
  HIGH: { label: '高', severity: 'warn', icon: 'pi pi-chevron-up' },
  URGENT: { label: '緊急', severity: 'danger', icon: 'pi pi-exclamation-triangle' },
}
</script>

<template>
  <Tag
    :value="config[priority]?.label ?? priority"
    :severity="config[priority]?.severity ?? 'secondary'"
    :icon="config[priority]?.icon"
    rounded
  />
</template>
