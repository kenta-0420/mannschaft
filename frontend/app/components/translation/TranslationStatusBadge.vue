<script setup lang="ts">
import type { TranslationStatus } from '~/types/translation'

const props = defineProps<{
  status: TranslationStatus
}>()

const { t } = useI18n()

const badge = computed(() => {
  const map: Record<TranslationStatus, { severity: string; label: string }> = {
    DRAFT: { severity: 'secondary', label: t('translation.status_draft') },
    IN_REVIEW: { severity: 'warn', label: t('translation.status_in_review') },
    APPROVED: { severity: 'info', label: t('translation.status_approved') },
    PUBLISHED: { severity: 'success', label: t('translation.status_published') },
    STALE: { severity: 'warn', label: t('translation.status_stale') },
    REJECTED: { severity: 'danger', label: t('translation.status_rejected') },
  }
  return map[props.status] ?? { severity: 'secondary', label: props.status }
})
</script>

<template>
  <Tag :severity="badge.severity" :value="badge.label" />
</template>
