<script setup lang="ts">
import type { ContentType } from '~/types/search'

const props = defineProps<{
  typeCounts: Record<string, number>
  activeType: ContentType | 'ALL'
}>()

const emit = defineEmits<{
  select: [type: ContentType | 'ALL']
}>()

const typeLabels: Record<string, string> = {
  ALL: 'すべて',
  POST: '投稿',
  MESSAGE: 'メッセージ',
  THREAD: 'スレッド',
  ARTICLE: '記事',
  FILE: 'ファイル',
  USER: 'ユーザー',
  TEAM: 'チーム',
  ORGANIZATION: '組織',
  ACTIVITY: '活動記録',
}

const totalCount = computed(() =>
  Object.values(props.typeCounts).reduce((sum, c) => sum + c, 0),
)

const tabs = computed(() => {
  const items = [{ type: 'ALL' as const, label: 'すべて', count: totalCount.value }]
  for (const [type, count] of Object.entries(props.typeCounts)) {
    if (count > 0) {
      items.push({ type: type as ContentType, label: typeLabels[type] ?? type, count })
    }
  }
  return items
})
</script>

<template>
  <div class="flex flex-wrap gap-2">
    <Button
      v-for="tab in tabs"
      :key="tab.type"
      :label="`${tab.label} (${tab.count})`"
      :severity="activeType === tab.type ? 'primary' : 'secondary'"
      :outlined="activeType !== tab.type"
      size="small"
      @click="emit('select', tab.type)"
    />
  </div>
</template>
