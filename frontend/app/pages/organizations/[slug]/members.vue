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
const router = useRouter()
const orgSlug = computed(() => String(route.params.slug))

const { isAdmin, isAdminOrDeputy, displayName, refresh } = useOrgShellContext()

/**
 * オーナー委譲オファーの承諾/辞退カードを表示するための offerId（`?offerId=` クエリ由来）。
 * 打診通知の actionUrl がこのディープリンク形式
 * （`/organizations/{slug}/members?offerId=...`）を指す。
 */
const offerIdFromQuery = computed<string | null>(() => {
  const v = route.query.offerId
  return typeof v === 'string' && v.length > 0 ? v : null
})

/** 承諾/辞退が確定したらクエリを除去し、ロール・権限を再取得して画面を最新化する。 */
async function onOfferResolved() {
  await refresh()
  const query = { ...route.query }
  delete query.offerId
  await router.replace({ query })
}

</script>

<template>
  <div class="mt-4">
    <TransferOwnershipOfferCard
      v-if="offerIdFromQuery"
      class="mb-4"
      scope-type="organization"
      :slug="orgSlug"
      :offer-id="offerIdFromQuery"
      :scope-name="displayName"
      @resolved="onOfferResolved"
    />
    <MemberTable
      scope-type="organization"
      :scope-id="orgSlug"
      :can-change-role="isAdminOrDeputy"
      :can-remove="isAdminOrDeputy"
    />
    <!-- オーナー委譲の打診は ADMIN 本人のみ。権限変更は相手の承諾時に行う。 -->
    <TransferOwnershipPanel
      v-if="isAdmin"
      class="mt-6"
      scope-type="organization"
      :scope-slug="orgSlug"
      :scope-name="displayName"
      @offered="refresh"
    />
  </div>
</template>
