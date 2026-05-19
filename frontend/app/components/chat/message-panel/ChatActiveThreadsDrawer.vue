<script setup lang="ts">
export interface ActiveThreadItem {
  id: number
  body: string
  replyCount: number
  lastReplyAt: string | null
  lastReplyPreview: string | null
  createdAt: string
}

defineProps<{
  visible: boolean
  count: number
  threads: ActiveThreadItem[]
  loading: boolean
  hasMore: boolean
  nextCursor: string | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  selectThread: [threadId: number]
  loadMore: [cursor: string]
}>()

function onUpdateVisible(value: boolean) {
  emit('update:visible', value)
}
</script>

<template>
  <Drawer
    :visible="visible"
    :header="$t('chat.thread.activeThreads', { count })"
    position="right"
    style="width: 360px"
    @update:visible="onUpdateVisible"
  >
    <div v-if="loading && threads.length === 0" class="flex justify-center py-8">
      <ProgressSpinner style="width: 32px; height: 32px" />
    </div>
    <div v-else-if="threads.length === 0" class="py-8 text-center text-sm text-surface-400">
      進行中のスレッドはありません
    </div>
    <ul v-else class="divide-y divide-surface-100">
      <li
        v-for="thread in threads"
        :key="thread.id"
        class="cursor-pointer px-4 py-3 hover:bg-surface-50"
        @click="emit('selectThread', thread.id)"
      >
        <p class="line-clamp-2 text-sm">{{ thread.body }}</p>
        <div class="mt-1 flex items-center gap-2 text-xs text-surface-400">
          <span>{{ $t('chat.thread.replyCount', { count: thread.replyCount }) }}</span>
          <span v-if="thread.lastReplyPreview" class="truncate">{{ thread.lastReplyPreview }}</span>
        </div>
      </li>
    </ul>
    <div v-if="hasMore" class="flex justify-center py-2">
      <Button
        :label="$t('label.loadMore')"
        text
        size="small"
        :loading="loading"
        @click="emit('loadMore', nextCursor!)"
      />
    </div>
  </Drawer>
</template>
