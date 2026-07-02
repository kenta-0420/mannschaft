<script setup lang="ts">
/**
 * チーム詳細「メンバー」タブ（永続シェル配下の子ルート）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { isAdminOrDeputy } = useTeamShellContext()
</script>

<template>
  <div class="mt-4">
    <MemberTable
      scope-type="team"
      :scope-id="teamSlug"
      :can-change-role="isAdminOrDeputy"
      :can-remove="isAdminOrDeputy"
    />
  </div>
</template>
