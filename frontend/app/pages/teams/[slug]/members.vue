<script setup lang="ts">
/**
 * チーム詳細「メンバー」タブ（永続シェル配下の子ルート）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 *
 * # ネストルートとの共存
 *  `pages/teams/[slug]/members/[userId]/match-analytics.vue` が存在するため、本 `members.vue`
 *  は同時にそのネスト親レイアウトになる（Nuxt の同名ファイル+ディレクトリ規則）。
 *  そのため深いルート（/members/{userId}/…）では MemberTable ではなく <NuxtPage/> を
 *  描画してネスト子（match-analytics 等）へ委譲する。ちょうど `/members` のときだけ
 *  メンバー一覧を表示する。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const router = useRouter()
const teamSlug = computed(() => String(route.params.slug))

const { isAdminOrDeputy, displayName, refresh, isAdmin, canManagePermissionGroups } = useTeamShellContext()

/** 権限グループ割当ダイアログ（F01.2.1 8-D。チーム ADMIN・管理レンズ ON のときだけ入口を出す）。 */
type PermissionGroupTarget = { userId: number, displayName: string, roleName: string, permissionGroups?: { id: number, name: string }[] }
const permissionGroupTarget = ref<PermissionGroupTarget | null>(null)
const memberTable = ref<{ refresh: () => Promise<void> } | null>(null)
const showPermissionGroups = ref(false)

function openPermissionGroups(member: PermissionGroupTarget) {
  permissionGroupTarget.value = member
  showPermissionGroups.value = true
}

/**
 * オーナー委譲オファーの承諾/辞退カードを表示するための offerId（`?offerId=` クエリ由来）。
 * 打診通知の actionUrl がこのディープリンク形式
 * （`/teams/{slug}/members?offerId=...`）を指す。
 */
const offerIdFromQuery = computed<string | null>(() => {
  const v = route.query.offerId
  return typeof v === 'string' && v.length > 0 ? v : null
})
/** ちょうど `/teams/{slug}/members`（ネスト子なし）か。 */
const isMembersRoot = computed<boolean>(() => {
  const last = route.path.replace(/\/+$/, '').split('/').pop() ?? ''
  return last === 'members'
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
  <div>
    <div v-if="isMembersRoot" class="mt-4">
      <TransferOwnershipOfferCard
        v-if="offerIdFromQuery"
        class="mb-4"
        scope-type="team"
        :slug="teamSlug"
        :offer-id="offerIdFromQuery"
        :scope-name="displayName"
        @resolved="onOfferResolved"
      />
      <MemberTable
        ref="memberTable"
        scope-type="team"
        :scope-id="teamSlug"
        :can-change-role="isAdminOrDeputy"
        :can-remove="isAdminOrDeputy"
        :can-assign-permission-groups="canManagePermissionGroups"
        @assign-permission-groups="openPermissionGroups"
      />
      <TeamMemberPermissionGroupsDialog
        v-if="canManagePermissionGroups"
        v-model:visible="showPermissionGroups"
        :team-slug="teamSlug"
        :member="permissionGroupTarget"
        @assigned="memberTable?.refresh()"
      />
      <!-- オーナー委譲の打診は ADMIN 本人のみ。権限変更は相手の承諾時に行う。 -->
      <TransferOwnershipPanel
        v-if="isAdmin"
        class="mt-6"
        scope-type="team"
        :scope-slug="teamSlug"
        :scope-name="displayName"
        @offered="refresh"
      />
    </div>
    <!-- ネスト子（/members/{userId}/match-analytics 等）へ委譲 -->
    <NuxtPage v-else />
  </div>
</template>
