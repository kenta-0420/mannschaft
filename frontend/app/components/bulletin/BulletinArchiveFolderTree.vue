<script setup lang="ts">
import type { ArchiveFolderTreeNode } from '~/types/bulletin'

/**
 * 保管庫フォルダツリー（再帰）。
 *
 * - 「未分類」（保管庫直下）を先頭に固定表示する。
 * - 各フォルダをアイコン + 色 + threadCount バッジで表示。
 * - ADMIN / DEPUTY_ADMIN（canManage）には各フォルダの編集・削除・子追加メニューを出す。
 * - 子フォルダは本コンポーネント自身を再帰的に描画する。
 *
 * 選択フォルダの識別子:
 *   - null  = 未分類（保管庫直下）
 *   - 'all' = 全保管庫横断
 *   - UUID  = 個別フォルダ
 */
interface Props {
  nodes: ArchiveFolderTreeNode[]
  /** 現在選択中のフォルダ ID（null=未分類 / 'all'=全件 / UUID）。 */
  selectedId: string | null
  /** 未分類スレッド件数（ルートレベルのみ表示）。 */
  unfiledThreadCount?: number
  canManage?: boolean
  /** 再帰描画時のネスト深さ（インデント計算用）。 */
  depth?: number
  /** ルート（最上位）描画かどうか。未分類・全件行はルートのみ表示。 */
  isRoot?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  unfiledThreadCount: 0,
  canManage: false,
  depth: 0,
  isRoot: true,
})

const emit = defineEmits<{
  /** フォルダ選択。null=未分類 / 'all'=全件 / UUID。 */
  select: [folderId: string | null]
  /** フォルダ編集要求。 */
  edit: [folder: ArchiveFolderTreeNode]
  /** フォルダ削除要求。 */
  remove: [folder: ArchiveFolderTreeNode]
  /** 子フォルダ作成要求（parentFolderId を渡す）。 */
  addChild: [parentFolderId: string | null]
}>()

function indentStyle(depth: number): Record<string, string> {
  return { paddingLeft: `${depth * 1}rem` }
}
</script>

<template>
  <div class="flex flex-col gap-0.5">
    <!-- 全件横断 + 未分類（ルートのみ） -->
    <template v-if="props.isRoot">
      <button
        type="button"
        class="flex items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
        :class="props.selectedId === 'all' ? 'bg-primary-50 font-semibold text-primary-700 dark:bg-primary-900/30' : ''"
        @click="emit('select', 'all')"
      >
        <i class="pi pi-inbox text-surface-500" />
        <span class="flex-1 truncate">{{ $t('bulletin.archive.allArchived') }}</span>
      </button>

      <button
        type="button"
        class="flex items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
        :class="props.selectedId === null ? 'bg-primary-50 font-semibold text-primary-700 dark:bg-primary-900/30' : ''"
        @click="emit('select', null)"
      >
        <i class="pi pi-folder-open text-surface-500" />
        <span class="flex-1 truncate">{{ $t('bulletin.archive.unfiled') }}</span>
        <span
          v-if="props.unfiledThreadCount > 0"
          class="rounded-full bg-surface-200 px-2 py-0.5 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
        >
          {{ props.unfiledThreadCount }}
        </span>
      </button>

      <div class="my-1 border-t border-surface-200 dark:border-surface-700" />
    </template>

    <!-- フォルダノード（再帰） -->
    <template v-for="folder in props.nodes" :key="folder.id">
      <div
        class="group flex items-center gap-1 rounded-md pr-1 transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
        :class="props.selectedId === folder.id ? 'bg-primary-50 dark:bg-primary-900/30' : ''"
        :style="indentStyle(props.depth)"
      >
        <button
          type="button"
          class="flex min-w-0 flex-1 items-center gap-2 px-2 py-1.5 text-left text-sm"
          :class="props.selectedId === folder.id ? 'font-semibold text-primary-700' : ''"
          @click="emit('select', folder.id)"
        >
          <i
            :class="`pi ${folder.icon || 'pi-folder'}`"
            :style="folder.color ? { color: folder.color } : { color: 'var(--p-surface-500)' }"
          />
          <span class="flex-1 truncate">{{ folder.name }}</span>
          <span
            v-if="folder.threadCount > 0"
            class="rounded-full bg-surface-200 px-2 py-0.5 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
          >
            {{ folder.threadCount }}
          </span>
        </button>

        <!-- 管理メニュー（ADMIN/DEPUTY のみ） -->
        <div v-if="props.canManage" class="flex items-center opacity-0 transition-opacity group-hover:opacity-100">
          <button
            type="button"
            class="rounded p-1 text-surface-400 hover:text-primary-600"
            :title="$t('bulletin.archive.addSubFolder')"
            :aria-label="$t('bulletin.archive.addSubFolder')"
            @click.stop="emit('addChild', folder.id)"
          >
            <i class="pi pi-plus text-xs" />
          </button>
          <button
            type="button"
            class="rounded p-1 text-surface-400 hover:text-primary-600"
            :title="$t('bulletin.archive.editFolder')"
            :aria-label="$t('bulletin.archive.editFolder')"
            @click.stop="emit('edit', folder)"
          >
            <i class="pi pi-pencil text-xs" />
          </button>
          <button
            type="button"
            class="rounded p-1 text-surface-400 hover:text-red-600"
            :title="$t('bulletin.archive.deleteFolder')"
            :aria-label="$t('bulletin.archive.deleteFolder')"
            @click.stop="emit('remove', folder)"
          >
            <i class="pi pi-trash text-xs" />
          </button>
        </div>
      </div>

      <!-- 子フォルダ（自己再帰） -->
      <BulletinArchiveFolderTree
        v-if="folder.children && folder.children.length > 0"
        :nodes="folder.children"
        :selected-id="props.selectedId"
        :can-manage="props.canManage"
        :depth="props.depth + 1"
        :is-root="false"
        @select="(id) => emit('select', id)"
        @edit="(f) => emit('edit', f)"
        @remove="(f) => emit('remove', f)"
        @add-child="(id) => emit('addChild', id)"
      />
    </template>
  </div>
</template>
