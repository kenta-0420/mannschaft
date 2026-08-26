<script setup lang="ts">
/**
 * 組織詳細「権限グループ」タブ（永続シェル配下の子ルート・管理者のみ）。
 * ヘッダ・タブは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 * 直リンク防御として、権限確定後に非管理者はダッシュボードへ戻す（二重ガード）。
 */
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

const { isAdmin, roleName, permissionGroups } = useOrgShellContext()

watch(
  [roleName, isAdmin],
  () => {
    if (roleName.value && !isAdmin.value) {
      navigateTo(`/organizations/${orgSlug.value}`)
    }
  },
  { immediate: true },
)
</script>

<template>
  <OrgPermissionGroupList v-if="isAdmin" :groups="permissionGroups" />
</template>
