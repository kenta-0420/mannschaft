<script setup lang="ts">
import type { ShiftScheduleStatus } from '~/types/shift'
import { statusToI18nKey } from '~/utils/shiftStatus'

const props = defineProps<{
  status: ShiftScheduleStatus
}>()

const { t } = useI18n()

const severityMap: Record<ShiftScheduleStatus, string> = {
  DRAFT: 'secondary',
  COLLECTING: 'info',
  ADJUSTING: 'warn',
  PUBLISHED: 'success',
  ARCHIVED: 'contrast',
}

const severity = computed(() => severityMap[props.status] ?? 'secondary')
const label = computed(() => t(statusToI18nKey(props.status)))
</script>

<template>
  <Tag :value="label" :severity="severity" rounded />
</template>
