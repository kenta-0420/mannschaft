<script setup lang="ts">
/**
 * 組織詳細「所属チーム」タブ（永続シェル配下の子ルート）。
 * ヘッダ・タブは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 *
 * # 命名について
 *  既存の `organizations/[slug]/teams/search.vue`（組織内チーム検索）と物理衝突するため、
 *  タブのルートセグメントは `teams` ではなく `member-teams` にしている。
 *
 * # view 切替（F01.2.1 8-B）
 *  クエリ `view` で一覧 / 加盟申請（applications）/ 送った招待（invites）/ 申請の制限（restrictions）/
 *  チームグループ（groups。8-C）を切り替える。子ルートは作らない（設計書 §13）。
 *  管理系 view は組織 ADMIN かつ管理レンズ ON のときだけ。権限のない者が直打ちしたら
 *  `view` を外した一覧表示へ戻す（AC-K02。BE も組織 ADMIN 専用で 403 を返す）。
 */
import type { LocationQueryRaw } from 'vue-router'
import { useOrgShellContext } from '~/composables/useOrgShellContext'
import {
  hasViewQuery,
  resolveMemberTeamsView,
  type MemberTeamsView,
} from '~/utils/memberTeamsView'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const orgSlug = computed(() => String(route.params.slug))

const { orgTeams, showTeamSearchLink, isScopeAdmin, adminLens, refreshOrgTeams } = useOrgShellContext()
const { listApplications } = useOrgTeamMembershipApi()

// 対象組織の ADMIN 割当を持つか。shell の isAdmin / roleName は SYSTEM_ADMIN を優先して返すため使わない。
// BE は対象組織の ADMIN 割当（AccessControlService.isAdmin）で 403 を返す。兼任者は許可し、SYSTEM_ADMIN のみは対象外。
const isOrgAdmin = isScopeAdmin

// F01.2.1 8-A: 加盟の設定への入口。組織 ADMIN かつ管理レンズ ON のときだけ出す（設計書 §13）。
const showAffiliationSettingsLink = computed(() => isOrgAdmin.value && adminLens.value)

/** 申請・招待・制限・グループの操作と切替を出してよいか。 */
const canManage = computed(() => isOrgAdmin.value && adminLens.value)

const activeView = computed<MemberTeamsView>(() => resolveMemberTeamsView(route.query.view, canManage.value))

/** 権限がない・未知の値で `view` が付いていたら、`view` を外した一覧表示へ戻す。 */
function omitView(query: typeof route.query): LocationQueryRaw {
  const rest: LocationQueryRaw = {}
  for (const [key, value] of Object.entries(query)) {
    if (key !== 'view') rest[key] = value
  }
  return rest
}

watch(
  [() => route.query.view, activeView],
  () => {
    if (activeView.value === 'teams' && hasViewQuery(route.query.view) && route.query.view !== 'teams') {
      void router.replace({ query: omitView(route.query) })
    }
  },
  { immediate: true },
)

function onSelect(view: MemberTeamsView) {
  const query = omitView(route.query)
  void router.push({ query: view === 'teams' ? query : { ...query, view } })
}

// 未処理の加盟申請件数（切替のバッジ）。申請一覧の total を 1 件取得して得る。
const pendingCount = ref<number | null>(null)
const countError = ref(false)

async function refreshPendingCount() {
  if (!canManage.value) return
  countError.value = false
  try {
    pendingCount.value = (await listApplications(orgSlug.value, 0, 1)).total
  }
  catch {
    // 件数の取得失敗は 0 件に見せず、バッジにエラー印を出す。
    pendingCount.value = null
    countError.value = true
  }
}

/** 申請一覧を取得できたら、その total で件数を更新する（取得失敗の印も消える）。 */
function onApplicationsLoaded(total: number) {
  pendingCount.value = total
  countError.value = false
}

/** 承認・拒否が成立した。件数に加え、承認で増えた加盟チームを親シェルの所属チーム一覧へ反映する。 */
async function onApplicationsChanged() {
  await Promise.all([refreshPendingCount(), refreshOrgTeams()])
}

onMounted(() => void refreshPendingCount())
watch(canManage, (allowed) => {
  if (allowed) void refreshPendingCount()
})

const showGuide = ref(false)

const viewTitleKey = computed(() => `teamAffiliation.view.${activeView.value}`)
</script>

<template>
  <div>
    <div
      v-if="showAffiliationSettingsLink"
      class="mt-4 flex justify-end"
    >
      <NuxtLink
        :to="`/organizations/${orgSlug}/settings/team-affiliation`"
        :aria-label="$t('teamAffiliation.settings.entry_button')"
        class="inline-flex items-center gap-2 rounded-md border border-surface-300 bg-surface-0 px-3 py-2 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-100 focus:outline-none focus:ring-2 focus:ring-primary-400 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-200"
        data-testid="team-affiliation-settings-link"
      >
        <i class="pi pi-cog" aria-hidden="true" />
        {{ $t('teamAffiliation.settings.entry_button') }}
      </NuxtLink>
    </div>

    <MemberTeamsViewSwitcher
      v-if="canManage"
      :current="activeView"
      :pending-count="pendingCount"
      :count-error="countError"
      @select="onSelect"
    />

    <template v-if="activeView === 'teams'">
      <div
        v-if="showTeamSearchLink"
        class="mt-4 flex justify-end"
      >
        <NuxtLink
          :to="`/organizations/${orgSlug}/teams/search`"
          :aria-label="$t('organizationTeamSearch.title')"
          class="inline-flex items-center gap-2 rounded-md border border-primary-300 bg-primary-50 px-3 py-2 text-sm font-medium text-primary-700 transition-colors hover:bg-primary-100 focus:outline-none focus:ring-2 focus:ring-primary-400"
        >
          <i class="pi pi-search" aria-hidden="true" />
          {{ $t('organizationTeamSearch.title') }}
        </NuxtLink>
      </div>
      <!-- グループ列・絞り込みは閲覧者全員（機能 on のとき）、割当 UI は組織 ADMIN・管理レンズ ON のみ（設計書 §13） -->
      <MemberTeamsTeamList
        :org-slug="orgSlug"
        :teams="orgTeams"
        :can-assign="canManage"
        @changed="refreshOrgTeams"
      />
    </template>

    <div v-else class="mt-4 space-y-4">
      <PageHeader
        :title="t(viewTitleKey)"
        size="sm"
        :back-to="`/organizations/${orgSlug}/member-teams`"
        help
        @help="showGuide = true"
      />
      <SectionCard>
        <MemberTeamsApplicationsView
          v-if="activeView === 'applications'"
          :org-slug="orgSlug"
          @loaded="onApplicationsLoaded"
          @changed="onApplicationsChanged"
        />
        <MemberTeamsInvitesView
          v-else-if="activeView === 'invites'"
          :org-slug="orgSlug"
        />
        <MemberTeamsRestrictionsView
          v-else-if="activeView === 'restrictions'"
          :org-slug="orgSlug"
        />
        <MemberTeamsGroupsView
          v-else
          :org-slug="orgSlug"
          @changed="refreshOrgTeams"
        />
      </SectionCard>
      <MemberTeamsGuideModal v-model:visible="showGuide" />
    </div>
  </div>
</template>
