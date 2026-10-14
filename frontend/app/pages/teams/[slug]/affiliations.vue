<script setup lang="ts">
/**
 * チーム詳細「所属組織」タブ（永続シェル配下の子ルート）。F01.2.1 8-D。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 *
 * # 画面の内容（設計書 §13）
 *  所属組織と離脱 / 組織から受け取った招待（承諾・辞退）/ 申請中（取下げ）/ 招待の制限（解除）、
 *  および「加盟先の組織を探す」導線（受付中の組織の検索）。
 *
 * # view 切替
 *  クエリ `view`（organizations=既定 / invites / applications / restrictions）で切り替え、子ルートは作らない
 *  （永続シェル配下の兄弟ルートには親側 <NuxtPage> が要る既知の罠を避ける）。
 *  通知（招待・除名・承認・拒否・期限切れ）のリンク先として、各 view にクエリで直接着地できる（AC-L06・E10）。
 *
 * # 権限
 *  画面を出すのはチームの加盟操作者（ADMIN、または MANAGE_ORG_AFFILIATION を付与された者）だけ。
 *  それ以外が直打ちした場合は親シェルの滞在ガードが `/teams/{slug}` へ戻す（AC-K03）。
 *  ガードが効く前・取得失敗時も、権限が無ければ view を描画しない＝管理 API を呼ばない。
 */
import type { LocationQueryRaw } from 'vue-router'
import { useTeamShellContext } from '~/composables/useTeamShellContext'
import {
  hasTeamAffiliationsViewQuery,
  resolveTeamAffiliationsView,
  TEAM_AFFILIATIONS_VIEWS,
  type TeamAffiliationsView,
} from '~/utils/teamAffiliationsView'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const teamSlug = computed(() => String(route.params.slug))

const { canManageOrgAffiliation } = useTeamShellContext()
const { listInvites } = useTeamAffiliationApi()

const activeView = computed<TeamAffiliationsView>(() => resolveTeamAffiliationsView(route.query.view))

function omitView(query: typeof route.query): LocationQueryRaw {
  const rest: LocationQueryRaw = {}
  for (const [key, value] of Object.entries(query)) {
    if (key !== 'view') rest[key] = value
  }
  return rest
}

/** 未知の `view` が付いていたら、`view` を外した URL へ戻す（所属組織を既定表示する）。 */
watch(
  () => route.query.view,
  (raw) => {
    const value = Array.isArray(raw) ? raw[0] : raw
    if (hasTeamAffiliationsViewQuery(raw) && !(TEAM_AFFILIATIONS_VIEWS as readonly string[]).includes(String(value))) {
      void router.replace({ query: omitView(route.query) })
    }
  },
  { immediate: true },
)

function onSelect(view: TeamAffiliationsView) {
  const query = omitView(route.query)
  void router.push({ query: view === 'organizations' ? query : { ...query, view } })
}

// 返事待ちの受信招待の件数（切替のバッジ）。受信招待一覧の total を 1 件取得して得る。
const inviteCount = ref<number | null>(null)
const countError = ref(false)
let countSeq = 0

async function refreshInviteCount() {
  if (!canManageOrgAffiliation.value) return
  const seq = ++countSeq
  const requestedSlug = teamSlug.value
  countError.value = false
  try {
    const result = await listInvites(requestedSlug, 0, 1)
    if (seq !== countSeq || requestedSlug !== teamSlug.value) return
    inviteCount.value = result.total
  }
  catch {
    if (seq !== countSeq) return
    // 件数の取得失敗は 0 件に見せず、バッジにエラー印を出す。
    inviteCount.value = null
    countError.value = true
  }
}

/** 招待一覧を取得できたら、その total で件数を更新する（取得失敗の印も消える）。 */
function onInvitesLoaded(total: number) {
  inviteCount.value = total
  countError.value = false
}

onMounted(() => void refreshInviteCount())
watch([canManageOrgAffiliation, teamSlug], ([allowed]) => {
  if (allowed) void refreshInviteCount()
})
onBeforeUnmount(() => {
  countSeq++
})

const showGuide = ref(false)
</script>

<template>
  <div v-if="canManageOrgAffiliation" class="mt-4 space-y-4">
    <PageHeader
      :title="t('teamAffiliations.title')"
      size="sm"
      :back-to="`/teams/${teamSlug}`"
      help
      @help="showGuide = true"
    >
      <template #actions>
        <NuxtLink
          to="/discover/organizations?acceptingTeamApplications=true"
          class="inline-flex items-center gap-2 rounded-md border border-primary-300 bg-primary-50 px-3 py-2 text-sm font-medium text-primary-700 transition-colors hover:bg-primary-100 focus:outline-none focus:ring-2 focus:ring-primary-400"
          data-testid="discover-organizations-link"
        >
          <i class="pi pi-search" aria-hidden="true" />
          {{ t('teamAffiliations.discover') }}
        </NuxtLink>
      </template>
    </PageHeader>

    <TeamAffiliationsViewSwitcher
      :current="activeView"
      :invite-count="inviteCount"
      :count-error="countError"
      @select="onSelect"
    />

    <SectionCard>
      <TeamAffiliationsOrganizationsView
        v-if="activeView === 'organizations'"
        :team-slug="teamSlug"
      />
      <TeamAffiliationsInvitesView
        v-else-if="activeView === 'invites'"
        :team-slug="teamSlug"
        @loaded="onInvitesLoaded"
        @changed="refreshInviteCount"
      />
      <TeamAffiliationsApplicationsView
        v-else-if="activeView === 'applications'"
        :team-slug="teamSlug"
      />
      <TeamAffiliationsRestrictionsView
        v-else
        :team-slug="teamSlug"
      />
    </SectionCard>

    <TeamAffiliationsGuideModal v-model:visible="showGuide" />
  </div>
</template>
