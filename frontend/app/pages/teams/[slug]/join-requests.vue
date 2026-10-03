<script setup lang="ts">
/**
 * チーム詳細「参加申請管理」タブ（永続シェル配下の子ルート・ADMIN/DEPUTY_ADMIN のみ）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 * 金型: `frontend/app/pages/teams/[slug]/supporters.vue`。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { isAdminOrDeputy, roleName, team } = useTeamShellContext()

const canView = computed(() => isAdminOrDeputy.value && !!team.value?.numericId)

// 直リンク防御: チーム/権限が確定した時点で条件を満たさなければ戻す。
watch(
  [roleName, isAdminOrDeputy],
  () => {
    if (team.value && roleName.value && !canView.value) {
      navigateTo(`/teams/${teamSlug.value}`)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="mt-4">
    <JoinRequestManagementPanel
      v-if="canView && team?.numericId"
      scope-type="team"
      :scope-id="team.numericId"
    />
  </div>
</template>
