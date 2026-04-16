<script setup lang="ts">
/**
 * F01.5 フレンドチーム 1 件の表示カード。
 *
 * 表示項目:
 * - friendTeamName（大きく表示、null 時は匿名フォールバック）
 * - establishedAt（相対時刻）
 * - 公開/非公開バッジ
 * - アクションメニュー（unfollow, visibility toggle）
 *
 * Props:
 *   friend               — フレンド関係 1 件
 *   teamId               — 自チーム ID（visibility API 呼び出し時に必要）
 *   canEdit              — ADMIN 相当の編集操作が可能か（unfollow ボタン表示）
 *   canToggleVisibility  — 公開設定操作が可能か（ADMIN のみ true）
 *
 * Emits:
 *   unfollow          — フォロー解除要求（親で確認ダイアログを出す）
 *   toggleVisibility  — 公開設定切替（新しい isPublic）
 */
import type { TeamFriendView } from '~/types/friends'

const props = defineProps<{
  friend: TeamFriendView
  teamId: number
  canEdit: boolean
  canToggleVisibility: boolean
}>()

const emit = defineEmits<{
  unfollow: [friend: TeamFriendView]
  toggleVisibility: [teamFriendId: number, isPublic: boolean]
}>()

const { t, locale } = useI18n()

const displayName = computed(
  () => props.friend.friendTeamName ?? t('friends.list.visibility_private'),
)

const establishedLabel = computed(() => {
  const dt = new Date(props.friend.establishedAt)
  if (Number.isNaN(dt.getTime())) return ''
  // ロケールに従って相対的かつ読みやすい形式で整形
  return dt.toLocaleString(locale.value, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
})

const menu = ref<{ toggle: (event: Event) => void } | null>(null)

const menuItems = computed(() => {
  const items: Array<Record<string, unknown>> = []
  if (props.canEdit) {
    items.push({
      label: t('friends.actions.unfollow'),
      icon: 'pi pi-user-minus',
      command: () => emit('unfollow', props.friend),
    })
  }
  return items
})

function openMenu(event: Event) {
  menu.value?.toggle(event)
}

function onVisibilityToggled(isPublic: boolean) {
  emit('toggleVisibility', props.friend.teamFriendId, isPublic)
}
</script>

<template>
  <SectionCard>
    <div class="flex items-start justify-between gap-4">
      <div class="flex-1 min-w-0">
        <p class="truncate text-lg font-semibold">{{ displayName }}</p>
        <div class="mt-1 flex flex-wrap items-center gap-2 text-sm text-surface-500">
          <span>{{ t('friends.list.established_at') }}: {{ establishedLabel }}</span>
          <Tag
            :value="friend.isPublic
              ? t('friends.list.visibility_public')
              : t('friends.list.visibility_private')"
            :severity="friend.isPublic ? 'success' : 'secondary'"
            class="text-xs"
          />
        </div>
      </div>

      <div class="flex items-center gap-2">
        <FriendVisibilityToggle
          :team-friend-id="friend.teamFriendId"
          :team-id="teamId"
          :is-public="friend.isPublic"
          :disabled="!canToggleVisibility"
          @toggled="onVisibilityToggled"
        />
        <Button
          v-if="menuItems.length > 0"
          icon="pi pi-ellipsis-v"
          text
          rounded
          :aria-label="t('friends.actions.toggle_visibility')"
          @click="openMenu"
        />
        <Menu
          v-if="menuItems.length > 0"
          ref="menu"
          :model="menuItems"
          popup
        />
      </div>
    </div>
  </SectionCard>
</template>
