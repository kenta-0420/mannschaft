<script setup lang="ts">
import type { QuickMemoResponse } from '~/types/quickMemo'

const props = defineProps<{
  memo: QuickMemoResponse
}>()

const emit = defineEmits<{
  archive: [id: number]
  restore: [id: number]
  delete: [id: number]
  undelete: [id: number]
  convert: [memo: QuickMemoResponse]
  click: [memo: QuickMemoResponse]
}>()

const { t } = useI18n()

const nextReminder = computed(() => {
  return (
    props.memo.reminders
      .filter((r) => r.scheduledAt && !r.sentAt)
      .sort((a, b) => (a.scheduledAt! < b.scheduledAt! ? -1 : 1))[0] ?? null
  )
})

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString('ja-JP', { month: 'short', day: 'numeric' })
}
</script>

<template>
  <div
    class="group cursor-pointer rounded-xl border border-surface-200 bg-surface-0 p-4 shadow-sm transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
    @click="emit('click', memo)"
  >
    <!-- タイトル -->
    <p class="truncate font-medium text-surface-800 dark:text-surface-100">{{ memo.title }}</p>

    <!-- 本文（最大3行）-->
    <p
      v-if="memo.body"
      class="mt-1 line-clamp-3 text-sm text-surface-500 dark:text-surface-400"
    >
      {{ memo.body }}
    </p>

    <!-- タグ -->
    <div v-if="memo.tags.length > 0" class="mt-2 flex flex-wrap gap-1">
      <span
        v-for="tag in memo.tags"
        :key="tag.id"
        class="rounded-full px-2 py-0.5 text-xs text-white"
        :style="{ backgroundColor: tag.color ?? '#6366f1' }"
      >
        {{ tag.name }}
      </span>
    </div>

    <!-- リマインダー -->
    <div v-if="nextReminder?.scheduledAt" class="mt-2 flex items-center gap-1 text-xs text-amber-600">
      <i class="pi pi-bell" />
      {{ formatDate(nextReminder.scheduledAt) }}
    </div>

    <!-- 画像サムネイル（1枚目） -->
    <div v-if="memo.attachments.length > 0" class="mt-2">
      <span class="text-xs text-surface-400">
        <i class="pi pi-image mr-1" />{{ memo.attachments.length }}
      </span>
    </div>

    <!-- フッター: 日時 + アクション -->
    <div class="mt-3 flex items-center justify-between">
      <span class="text-xs text-surface-400">{{ formatDate(memo.createdAt) }}</span>

      <div class="flex gap-1 opacity-0 transition-opacity group-hover:opacity-100" @click.stop>
        <!-- UNSORTED のみアーカイブ・TODO変換 -->
        <template v-if="memo.status === 'UNSORTED'">
          <Button
            icon="pi pi-check-square"
            rounded
            text
            size="small"
            :title="t('quick_memo.action.convert')"
            @click="emit('convert', memo)"
          />
          <Button
            icon="pi pi-inbox"
            rounded
            text
            size="small"
            :title="t('quick_memo.action.archive')"
            @click="emit('archive', memo.id)"
          />
          <Button
            icon="pi pi-trash"
            rounded
            text
            severity="danger"
            size="small"
            :title="t('button.delete')"
            @click="emit('delete', memo.id)"
          />
        </template>

        <!-- ARCHIVED は復元 + 削除 -->
        <template v-else-if="memo.status === 'ARCHIVED'">
          <Button
            icon="pi pi-undo"
            rounded
            text
            size="small"
            :title="t('quick_memo.action.restore')"
            @click="emit('restore', memo.id)"
          />
          <Button
            icon="pi pi-trash"
            rounded
            text
            severity="danger"
            size="small"
            :title="t('button.delete')"
            @click="emit('delete', memo.id)"
          />
        </template>

        <!-- ゴミ箱（deletedAt あり）は復元のみ -->
        <template v-else>
          <Button
            icon="pi pi-undo"
            rounded
            text
            size="small"
            :title="t('quick_memo.trash.restore')"
            @click="emit('undelete', memo.id)"
          />
        </template>
      </div>
    </div>
  </div>
</template>
