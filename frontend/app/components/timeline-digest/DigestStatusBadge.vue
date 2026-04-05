<script setup lang="ts">
defineProps<{
  status: 'GENERATING' | 'GENERATED' | 'PUBLISHED' | 'DISCARDED' | 'FAILED'
}>()

const statusMap: Record<string, { severity: string; icon?: string; label: string }> = {
  GENERATING: { severity: 'info', icon: 'pi pi-spin pi-spinner', label: '生成中' },
  GENERATED: { severity: 'success', label: '生成済' },
  PUBLISHED: { severity: 'primary', label: '公開中' },
  DISCARDED: { severity: 'secondary', label: '破棄' },
  FAILED: { severity: 'danger', label: '失敗' },
}
</script>

<template>
  <Tag
    :severity="statusMap[status]?.severity"
    :icon="statusMap[status]?.icon"
    :value="statusMap[status]?.label ?? status"
  />
</template>
