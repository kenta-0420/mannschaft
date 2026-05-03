<script setup lang="ts">
defineProps<{
  status: 'GENERATING' | 'GENERATED' | 'PUBLISHED' | 'DISCARDED' | 'FAILED'
}>()

const { t } = useI18n()

const statusMap = computed<Record<string, { severity: string; icon?: string; label: string }>>(() => ({
  GENERATING: { severity: 'info', icon: 'pi pi-spin pi-spinner', label: t('timeline_digest.status_generating') },
  GENERATED: { severity: 'success', label: t('timeline_digest.status_generated') },
  PUBLISHED: { severity: 'primary', label: t('timeline_digest.status_published') },
  DISCARDED: { severity: 'secondary', label: t('timeline_digest.status_discarded') },
  FAILED: { severity: 'danger', label: t('timeline_digest.status_failed') },
}))
</script>

<template>
  <Tag
    :severity="statusMap[status]?.severity"
    :icon="statusMap[status]?.icon"
    :value="statusMap[status]?.label ?? status"
  />
</template>
