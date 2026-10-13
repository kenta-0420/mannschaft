<script setup lang="ts">
/**
 * F01.2.1 8-B — チームグループ管理 view の枠。
 *
 * 8-B は `view=groups` を受ける枠だけ用意し、作成・改名・並び替え・削除は 8-C がこのコンポーネントを差し替えて実装する。
 */
defineProps<{
  orgSlug: string
}>()
</script>

<template>
  <p class="text-sm text-surface-500" data-testid="member-teams-groups-placeholder">
    {{ $t('orgMemberTeams.groups_placeholder') }}
  </p>
</template>
