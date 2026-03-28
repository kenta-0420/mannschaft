<script setup lang="ts">
import type { SearchResult } from '~/types/search'

defineProps<{
  result: SearchResult
}>()

const typeIcon = (type: string) => {
  const map: Record<string, string> = {
    POST: 'pi pi-comment',
    MESSAGE: 'pi pi-comments',
    THREAD: 'pi pi-list',
    ARTICLE: 'pi pi-file',
    FILE: 'pi pi-paperclip',
    USER: 'pi pi-user',
    TEAM: 'pi pi-users',
    ORGANIZATION: 'pi pi-building',
    ACTIVITY: 'pi pi-calendar',
  }
  return map[type] ?? 'pi pi-circle'
}

const typeLabel = (type: string) => {
  const map: Record<string, string> = {
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
  return map[type] ?? type
}
</script>

<template>
  <NuxtLink :to="result.url" class="block rounded-lg border border-surface-200 p-4 transition-shadow hover:shadow-md dark:border-surface-700">
    <div class="flex items-start gap-3">
      <div class="mt-1 flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary">
        <i :class="typeIcon(result.type)" />
      </div>
      <div class="flex-1 min-w-0">
        <div class="flex items-center gap-2">
          <Badge :value="typeLabel(result.type)" severity="secondary" class="text-xs" />
          <span v-if="result.scope" class="text-xs text-surface-500">{{ result.scope.name }}</span>
        </div>
        <p v-if="result.title" class="mt-1 font-medium truncate">{{ result.title }}</p>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <p class="mt-1 text-sm text-surface-600 dark:text-surface-400 line-clamp-2" v-html="result.snippet" />
        <div class="mt-2 flex items-center gap-3 text-xs text-surface-400">
          <span v-if="result.author">
            <i class="pi pi-user mr-1" />{{ result.author.displayName }}
          </span>
          <span>{{ new Date(result.createdAt).toLocaleDateString('ja-JP') }}</span>
        </div>
      </div>
    </div>
  </NuxtLink>
</template>
