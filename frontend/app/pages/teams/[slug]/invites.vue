<script setup lang="ts">
/**
 * チーム詳細「招待」タブ（永続シェル配下の子ルート・管理者/副管理者のみ）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 * 直リンク防御として、権限確定後に非管理者はダッシュボードへ戻す（二重ガード）。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { isAdminOrDeputy, roleName } = useTeamShellContext()

// 直リンク防御: 権限が確定（roleName 非 null）した時点で管理権限が無ければ戻す。
watch(
  [roleName, isAdminOrDeputy],
  () => {
    if (roleName.value && !isAdminOrDeputy.value) {
      navigateTo(`/teams/${teamSlug.value}`)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="mt-4">
    <InviteTokenList
      v-if="isAdminOrDeputy"
      scope-type="team"
      :scope-id="teamSlug"
    />
  </div>
</template>
