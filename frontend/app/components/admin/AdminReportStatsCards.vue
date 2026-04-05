<script setup lang="ts">
import type { ReportStatsResponse } from '~/types/admin-report'

defineProps<{
  stats: ReportStatsResponse
}>()
</script>

<template>
  <div class="mb-4 grid grid-cols-3 gap-3 md:grid-cols-6">
    <Card>
      <template #content>
        <p class="text-xs text-surface-500">未対応</p>
        <p class="text-xl font-bold text-red-500">{{ stats.pendingCount }}</p>
      </template>
    </Card>
    <Card>
      <template #content>
        <p class="text-xs text-surface-500">対応中</p>
        <p class="text-xl font-bold text-yellow-500">{{ stats.reviewingCount }}</p>
      </template>
    </Card>
    <Card>
      <template #content>
        <p class="text-xs text-surface-500">エスカレ</p>
        <p class="text-xl font-bold text-orange-500">{{ stats.escalatedCount }}</p>
      </template>
    </Card>
    <Card>
      <template #content>
        <p class="text-xs text-surface-500">解決済</p>
        <p class="text-xl font-bold text-green-500">{{ stats.resolvedCount }}</p>
      </template>
    </Card>
    <Card>
      <template #content>
        <p class="text-xs text-surface-500">却下</p>
        <p class="text-xl font-bold text-surface-400">{{ stats.dismissedCount }}</p>
      </template>
    </Card>
    <Card>
      <template #content>
        <p class="text-xs text-surface-500">合計</p>
        <p class="text-xl font-bold text-primary">{{ stats.totalCount }}</p>
      </template>
    </Card>
  </div>
</template>
