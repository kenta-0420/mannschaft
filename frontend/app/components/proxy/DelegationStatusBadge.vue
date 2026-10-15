<script setup lang="ts">
// PENDING=黄, ACCEPTED=緑, REJECTED=赤, CANCELLED=グレー
type DelegationStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'
const props = defineProps<{ status: DelegationStatus }>()
const { t } = useI18n()

const colorMap: Record<DelegationStatus, string> = {
  PENDING: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200',
  ACCEPTED: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
  REJECTED: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200',
  CANCELLED: 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400',
}
</script>

<template>
  <span
    class="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium"
    :class="colorMap[props.status]"
  >
    {{ t(`proxy.delegation.status.${props.status.toLowerCase()}`) }}
  </span>
</template>
