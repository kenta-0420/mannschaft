<script setup lang="ts">
import type { TranslationStatus } from '~/types/translation'

const props = defineProps<{
  status: TranslationStatus
}>()

const badgeMap: Record<TranslationStatus, { severity: string; label: string }> = {
  DRAFT: { severity: 'secondary', label: '下書き' },
  IN_REVIEW: { severity: 'warn', label: 'レビュー中' },
  APPROVED: { severity: 'info', label: '承認済み' },
  PUBLISHED: { severity: 'success', label: '公開中' },
  STALE: { severity: 'warn', label: '要更新' },
  REJECTED: { severity: 'danger', label: '却下' },
}

const badge = computed(() => badgeMap[props.status] ?? { severity: 'secondary', label: props.status })
</script>

<template>
  <Tag :severity="badge.severity" :value="badge.label" />
</template>
