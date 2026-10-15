<script setup lang="ts">
/**
 * チーム詳細「サポーター管理」タブ（永続シェル配下の子ルート・管理者かつサポーター機能有効時のみ）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 * 直リンク防御として、権限/機能フラグ確定後に条件を満たさなければダッシュボードへ戻す。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { isAdmin, roleName, team } = useTeamShellContext()

const supporterEnabled = computed(() => team.value?.visibility?.supporterEnabled ?? false)
const canView = computed(() => isAdmin.value && supporterEnabled.value)

// 直リンク防御: チーム/権限が確定した時点で条件を満たさなければ戻す。
watch(
  [roleName, isAdmin, supporterEnabled],
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
    <SupporterManagementPanel
      v-if="canView"
      scope-type="team"
      :scope-id="teamSlug"
    />
  </div>
</template>
