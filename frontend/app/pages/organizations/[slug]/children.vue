<script setup lang="ts">
/**
 * 組織詳細「下位組織」タブ（永続シェル配下の子ルート）。
 * ヘッダ・タブは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 * 直リンク防御として、表示条件（子 0 件かつ非 ADMIN）を満たさなければダッシュボードへ戻す。
 */
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

const {
  children,
  hierarchyLoading,
  childrenHasNext,
  fetchChildren,
  showChildrenTab,
  roleName,
} = useOrgShellContext()

// 直リンク防御: 権限が確定した時点で表示条件を満たさなければ戻す。
watch(
  [roleName, showChildrenTab],
  () => {
    if (roleName.value && !showChildrenTab.value) {
      navigateTo(`/organizations/${orgSlug.value}`)
    }
  },
  { immediate: true },
)
</script>

<template>
  <OrgChildrenGrid
    v-if="showChildrenTab"
    :children="children"
    :loading="hierarchyLoading"
    :has-next="childrenHasNext"
    @load-more="fetchChildren(false)"
  />
</template>
