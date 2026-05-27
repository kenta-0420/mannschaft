<script setup lang="ts">
import DelegationStatusBadge from '~/components/proxy/DelegationStatusBadge.vue'

interface DelegationItem {
  id: string
  delegatorName: string
  delegateName: string
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'
  reason: string | null
  createdAt: string
}

const props = defineProps<{ item: DelegationItem }>()
const { t } = useI18n()
</script>

<template>
  <div class="flex items-center justify-between rounded-lg border border-gray-200 bg-white p-3 dark:border-gray-700 dark:bg-gray-800">
    <div class="flex flex-col gap-1">
      <div class="flex items-center gap-2 text-sm">
        <span class="font-medium text-gray-900 dark:text-gray-100">{{ item.delegatorName }}</span>
        <span class="text-gray-400">→</span>
        <span class="font-medium text-gray-900 dark:text-gray-100">{{ item.delegateName }}</span>
      </div>
      <div v-if="item.reason" class="text-xs text-gray-500 dark:text-gray-400">
        {{ item.reason }}
      </div>
    </div>
    <DelegationStatusBadge :status="item.status" />
  </div>
</template>
