<script setup lang="ts">
import type { ContentRanking as ContentRankingType } from '~/types/analytics'

defineProps<{
  rankings: ContentRankingType[]
}>()

const typeIcon = (type: string) => {
  const map: Record<string, string> = {
    ARTICLE: 'pi pi-file',
    ACTIVITY: 'pi pi-calendar',
    PAGE: 'pi pi-globe',
    TEAM: 'pi pi-users',
  }
  return map[type] ?? 'pi pi-circle'
}
</script>

<template>
  <div>
    <h3 class="mb-3 text-sm font-medium">人気コンテンツ</h3>
    <div v-if="rankings.length === 0" class="py-4 text-center text-sm text-surface-500">
      データがありません
    </div>
    <div v-else class="space-y-2">
      <NuxtLink
        v-for="(item, index) in rankings"
        :key="item.contentId"
        :to="item.url"
        class="flex items-center gap-3 rounded-lg border border-surface-200 p-3 transition-shadow hover:shadow-sm dark:border-surface-700"
      >
        <span class="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10 text-sm font-bold text-primary">
          {{ index + 1 }}
        </span>
        <i :class="typeIcon(item.contentType)" class="text-surface-400" />
        <div class="flex-1 min-w-0">
          <p class="truncate text-sm font-medium">{{ item.title }}</p>
        </div>
        <div class="text-right text-xs text-surface-500">
          <p>{{ item.views }} PV</p>
          <p>{{ item.uniqueVisitors }} UU</p>
        </div>
      </NuxtLink>
    </div>
  </div>
</template>
