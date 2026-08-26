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

const { isAdmin, isAdminOrDeputy, displayName, refresh } = useOrgShellContext()

/**
 * CMP-051: オーナー譲渡後は自分が ADMIN でなくなるため、シェルの組織・権限を
 * 再解決して権限依存 UI（管理者専用タブ・操作ボタン）を即座に整合させる。
 */
async function onOwnershipTransferred(): Promise<void> {
  await refresh()
}
</script>

<template>
  <div class="mt-4">
    <MemberTable
      scope-type="organization"
      :scope-id="orgSlug"
      :can-change-role="isAdminOrDeputy"
      :can-remove="isAdminOrDeputy"
    />
    <!-- CMP-051: オーナー譲渡は ADMIN 本人のみ（BE も最終判定を ADMIN 限定にしている） -->
    <TransferOwnershipPanel
      v-if="isAdmin"
      class="mt-6"
      scope-type="organization"
      :scope-slug="orgSlug"
      :scope-name="displayName"
      @transferred="onOwnershipTransferred"
    />
  </div>
</template>
