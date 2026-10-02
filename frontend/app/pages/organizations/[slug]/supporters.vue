<script setup lang="ts">
/**
 * 組織詳細「サポーター管理」タブ（永続シェル配下の子ルート・管理者かつサポーター機能有効時のみ）。
 * ヘッダ・タブは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 * 直リンク防御として、条件確定後に条件を満たさなければダッシュボードへ戻す。
 */
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

const { isAdmin, roleName, org } = useOrgShellContext()

const supporterEnabled = computed(() => org.value?.visibility?.supporterEnabled ?? false)
const canView = computed(() => isAdmin.value && supporterEnabled.value)

watch(
  [roleName, isAdmin, supporterEnabled],
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
    <SupporterManagementPanel
      v-if="canView"
      scope-type="organization"
      :scope-id="orgSlug"
    />
  </div>
</template>
