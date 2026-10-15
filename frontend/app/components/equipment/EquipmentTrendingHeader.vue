<script setup lang="ts">
defineProps<{
  totalTeams: number
  calculatedAt: string
  optOut: boolean
}>()

const emit = defineEmits<{
  openOptOut: []
}>()

function formatDate(isoStr: string): string {
  const d = new Date(isoStr)
  const now = new Date()
  const diffH = Math.floor((now.getTime() - d.getTime()) / 3600000)
  if (diffH < 24) return `${diffH}時間前更新`
  const diffD = Math.floor(diffH / 24)
  return `${diffD}日前更新`
}
</script>

<template>
  <div class="mb-3">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-semibold text-surface-700">{{ $t('equipment.trending.title') }}</h3>
      <button
        class="rounded p-1 text-surface-400 hover:bg-surface-100 hover:text-surface-600 transition-colors"
        :title="$t('equipment.trending.settings')"
        @click="emit('openOptOut')"
      >
        <i class="pi pi-cog text-sm" />
      </button>
    </div>
    <p class="mt-1 text-xs text-surface-400 leading-relaxed">
      {{ $t('equipment.trending.disclaimer', { count: totalTeams }) }}
    </p>
    <p class="mt-0.5 text-xs text-surface-300">{{ formatDate(calculatedAt) }}</p>
  </div>
</template>
