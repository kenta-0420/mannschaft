<script setup lang="ts">
/**
 * F01.5 フレンドフォルダ 1 件の表示カード。
 *
 * 表示項目:
 * - カラーインジケーター（フォルダ色）
 * - フォルダ名
 * - 説明（任意）
 * - メンバー数
 * - アクションメニュー（編集・メンバー管理・削除）
 *
 * Props:
 *   folder  — 表示対象のフォルダ
 *   canEdit — ADMIN / MANAGE_FRIEND_TEAMS を持つ DEPUTY_ADMIN のみ true
 *
 * Emits:
 *   edit           — 編集要求
 *   delete         — 削除要求
 *   manageMembers  — メンバー管理パネル起動要求
 */
import type { TeamFriendFolderView } from '~/types/friendFolders'

const props = defineProps<{
  folder: TeamFriendFolderView
  canEdit: boolean
}>()

const emit = defineEmits<{
  edit: [folder: TeamFriendFolderView]
  delete: [folderId: number]
  manageMembers: [folder: TeamFriendFolderView]
}>()

const { t } = useI18n()

const menu = ref<{ toggle: (event: Event) => void } | null>(null)

const menuItems = computed(() => {
  if (!props.canEdit) return []
  return [
    {
      label: t('folders.actions.edit'),
      icon: 'pi pi-pencil',
      command: () => emit('edit', props.folder),
    },
    {
      label: t('folders.actions.manage_members'),
      icon: 'pi pi-users',
      command: () => emit('manageMembers', props.folder),
    },
    {
      separator: true,
    },
    {
      label: t('folders.actions.delete'),
      icon: 'pi pi-trash',
      class: 'text-red-500',
      command: () => emit('delete', props.folder.id),
    },
  ]
})

function openMenu(event: Event) {
  menu.value?.toggle(event)
}
</script>

<template>
  <SectionCard>
    <div class="flex items-start justify-between gap-4">
      <div class="flex flex-1 min-w-0 items-start gap-3">
        <!-- カラーインジケーター -->
        <div
          class="mt-1 h-4 w-4 flex-shrink-0 rounded-full border border-surface-200 dark:border-surface-700"
          :style="{ backgroundColor: folder.color || '#6B7280' }"
          aria-hidden="true"
        />

        <!-- フォルダ情報 -->
        <div class="min-w-0 flex-1">
          <p class="truncate text-lg font-semibold">{{ folder.name }}</p>
          <p
            v-if="folder.description"
            class="mt-1 line-clamp-2 text-sm text-surface-500 dark:text-surface-400"
          >
            {{ folder.description }}
          </p>
          <div class="mt-2 flex flex-wrap items-center gap-2 text-sm text-surface-500">
            <Tag
              :value="t('folders.list.member_count', { count: folder.memberCount })"
              severity="secondary"
              class="text-xs"
            />
          </div>
        </div>
      </div>

      <!-- アクションメニュー -->
      <div v-if="canEdit" class="flex flex-shrink-0 items-center">
        <Button
          icon="pi pi-ellipsis-v"
          text
          rounded
          :aria-label="t('folders.actions.edit')"
          @click="openMenu"
        />
        <Menu ref="menu" :model="menuItems" popup />
      </div>
    </div>
  </SectionCard>
</template>
