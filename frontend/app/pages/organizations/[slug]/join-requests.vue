<script setup lang="ts">
/**
 * 組織詳細「参加申請管理」タブ（永続シェル配下の子ルート・ADMIN/DEPUTY_ADMIN のみ）。
 * ヘッダ・タブは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 * 金型: `frontend/app/pages/teams/[slug]/join-requests.vue`。
 */
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

const { isAdminOrDeputy, roleName, org } = useOrgShellContext()

const canView = computed(() => isAdminOrDeputy.value && !!org.value?.numericId)

// 直リンク防御: 組織/権限が確定した時点で条件を満たさなければ戻す。
watch(
  [roleName, isAdminOrDeputy],
  () => {
    if (org.value && roleName.value && !canView.value) {
      navigateTo(`/organizations/${orgSlug.value}`)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="mt-4">
    <JoinRequestManagementPanel
      v-if="canView && org?.numericId"
      scope-type="organization"
      :scope-id="org.numericId"
    />
  </div>
</template>
