<script setup lang="ts">
/**
 * 組織詳細「メンバー」タブ（永続シェル配下の子ルート）。
 * ヘッダ・タブは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 */
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

const { isAdminOrDeputy } = useOrgShellContext()
</script>

<template>
  <div class="mt-4">
    <MemberTable
      scope-type="organization"
      :scope-id="orgSlug"
      :can-change-role="isAdminOrDeputy"
      :can-remove="isAdminOrDeputy"
    />
  </div>
</template>
