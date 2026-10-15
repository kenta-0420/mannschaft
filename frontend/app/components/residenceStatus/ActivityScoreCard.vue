<script setup lang="ts">
const props = defineProps<{
  score: number
  residentName?: string
  lastSeenAt?: string | null
}>()

const { formatDate } = useDatetime()

// スコアに応じたリスクレベルを返す
const riskLevel = computed<'high' | 'mid' | 'low'>(() => {
  if (props.score >= 40) return 'high'
  if (props.score >= 20) return 'mid'
  return 'low'
})

const riskLabel = computed<string>(() => {
  if (riskLevel.value === 'high') return '高リスク'
  if (riskLevel.value === 'mid') return '中リスク'
  return '低リスク'
})

const scoreColorClass = computed<string>(() => {
  if (riskLevel.value === 'high') return 'text-red-500'
  if (riskLevel.value === 'mid') return 'text-orange-400'
  return 'text-green-500'
})

const borderColorClass = computed<string>(() => {
  if (riskLevel.value === 'high') return 'border-red-300'
  if (riskLevel.value === 'mid') return 'border-orange-300'
  return 'border-green-300'
})

const bgColorClass = computed<string>(() => {
  if (riskLevel.value === 'high') return 'bg-red-50 dark:bg-red-950/20'
  if (riskLevel.value === 'mid') return 'bg-orange-50 dark:bg-orange-950/20'
  return 'bg-green-50 dark:bg-green-950/20'
})

function formatLastSeen(iso: string | null | undefined): string {
  if (!iso) return ''
  return formatDate(iso)
}
</script>

<template>
  <div
    class="border rounded-lg p-4 flex flex-col items-center gap-2 min-w-32"
    :class="[borderColorClass, bgColorClass]"
  >
    <div v-if="residentName" class="text-sm font-medium text-gray-700 dark:text-gray-300 truncate w-full text-center">
      {{ residentName }}
    </div>

    <div class="text-4xl font-bold" :class="scoreColorClass">
      {{ score }}
    </div>

    <div class="text-xs font-semibold px-2 py-0.5 rounded-full border" :class="[scoreColorClass, borderColorClass]">
      {{ riskLabel }}
    </div>

    <div v-if="lastSeenAt" class="text-xs text-gray-500 dark:text-gray-400">
      最終活動: {{ formatLastSeen(lastSeenAt) }}
    </div>
  </div>
</template>
