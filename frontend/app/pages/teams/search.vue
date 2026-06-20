<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { t } = useI18n()
const teamApi = useTeamApi()
const teamStore = useTeamStore()
const { handleApiError } = useErrorHandler()
const notification = useNotification()

const followedTeamIds = ref<string[]>([])
const followingTeamIds = ref<string[]>([])

const myTeamSlugs = computed(() => new Set(teamStore.myTeams.map((team) => team.slug)))

async function followTeam(teamId: string, event: Event) {
  event.stopPropagation()
  followingTeamIds.value.push(teamId)
  try {
    await teamApi.followTeam(String(teamId))
    followedTeamIds.value.push(teamId)
    notification.success(t('teamHub.supporterSuccess'))
  } catch {
    notification.error(t('teamHub.supporterError'))
  } finally {
    followingTeamIds.value = followingTeamIds.value.filter((id) => id !== teamId)
  }
}

/** チーム検索 API レスポンス内の1件分（URLルーティングに必要な slug を含む）。 */
interface TeamSearchSummary {
  id: string
  /** チームスラッグ（URLルーティング用）。{@code /teams/{slug}} に使用する。 */
  slug: string
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  /** 都道府県コード（BE `prefectureCode` camelCase と 1:1、null 許容）。 */
  prefectureCode: string | null
  /** 市区町村コード（BE `cityCode` camelCase と 1:1、null 許容）。 */
  cityCode: string | null
  template: string
  memberCount: number
  supporterEnabled: boolean
  teamFriendCount: number
  supporterCount: number
}
const teams = ref<TeamSearchSummary[]>([])
const loading = ref(false)
const totalRecords = ref(0)
const currentPage = ref(0)
const pageSize = 20
const showCreateDialog = ref(false)

const { templateLabel } = useScopeLabels()
const route = useRoute()

/** F22.1: スコープ検索フォームからの遷移時に URL クエリ keyword を初期値として復元する。 */
const initialKeyword = ref('')

const searchParams = ref({
  keyword: '',
  prefecture: '',
  prefectureCode: '',
  template: '',
})

async function fetchTeams() {
  loading.value = true
  try {
    const result = await teamApi.searchTeams({
      keyword: searchParams.value.keyword || undefined,
      // F22.1 Phase2 足場C 第三陣: 地域はコード送信を優先（BE dual-support）。
      // 公開チーム検索 BE（PublicDiscoverController）は prefectureCode（camelCase）を受ける。
      prefectureCode: searchParams.value.prefectureCode || undefined,
      prefecture: searchParams.value.prefecture || undefined,
      template: searchParams.value.template || undefined,
      page: currentPage.value,
      size: pageSize,
    })
    teams.value = result.data
    totalRecords.value = result.meta.totalElements
  } catch (error) {
    handleApiError(error, t('teamHub.searchPageTitle'))
  } finally {
    loading.value = false
  }
}

function onSearch(params: {
  keyword: string
  prefecture: string
  prefectureCode: string
  template: string
}) {
  searchParams.value = {
    keyword: params.keyword,
    prefecture: params.prefecture,
    prefectureCode: params.prefectureCode,
    template: params.template,
  }
  currentPage.value = 0
  fetchTeams()
}

function onPageChange(event: { page: number }) {
  currentPage.value = event.page
  fetchTeams()
}

function onTeamCreated(entity: { id: string; name: string; slug: string }) {
  navigateTo(`/teams/${entity.slug}`)
}

function formatLocation(prefecture: string | null, city: string | null): string {
  return [prefecture, city].filter(Boolean).join(' ') || '-'
}

onMounted(() => {
  // F22.1 §2.9: URL クエリ keyword があれば検索フォーム初期値にセットして初期検索を実行。
  const kw = route.query.keyword
  if (typeof kw === 'string' && kw.length > 0) {
    initialKeyword.value = kw
    searchParams.value.keyword = kw
  }
  fetchTeams()
})
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <div class="mb-6 flex items-center gap-4">
      <PageHeader :title="$t('teamHub.searchPageTitle')" class="flex-1" back-to="/teams" />
      <Button
        :label="$t('teamHub.createTeam')"
        icon="pi pi-plus"
        @click="showCreateDialog = true"
      />
    </div>

    <div class="mb-6">
      <SearchBar
        :placeholder="$t('teamHub.searchPageTitle')"
        :show-template-filter="true"
        :initial-keyword="initialKeyword"
        @search="onSearch"
      />
    </div>

    <PageLoading v-if="loading" />

    <DashboardEmptyState
      v-else-if="teams.length === 0"
      icon="pi pi-search"
      :message="$t('teamHub.noSearchResults')"
    />

    <template v-else>
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div
          v-for="team in teams"
          :key="team.id"
          class="cursor-pointer rounded-lg border-2 border-surface-400 bg-surface-0 p-4 transition-shadow hover:shadow-md"
          @click="team.slug ? navigateTo(`/teams/${team.slug}`) : undefined"
        >
          <div class="mb-3 flex items-center gap-3">
            <Avatar
              :image="team.iconUrl ?? undefined"
              :label="team.iconUrl ? undefined : team.name.charAt(0)"
              shape="circle"
              size="large"
            />
            <div class="min-w-0 flex-1">
              <h3 class="truncate font-semibold">
                {{ team.nickname1 || team.name }}
              </h3>
              <Tag
                :value="templateLabel[team.template] ?? team.template"
                severity="info"
                class="text-xs"
              />
            </div>
          </div>
          <div class="flex items-center justify-between text-sm text-gray-500">
            <span><i class="pi pi-map-marker mr-1" />{{ formatLocation(team.prefecture, team.city) }}</span>
            <span><i class="pi pi-users mr-1" />{{ $t('teamHub.memberCount', { count: team.memberCount }) }}</span>
          </div>
          <div
            v-if="team.supporterEnabled && !myTeamSlugs.has(team.slug)"
            class="mt-3 border-t border-surface-100 pt-3"
          >
            <span
              v-if="followedTeamIds.includes(team.slug)"
              class="flex items-center gap-1 text-sm text-primary"
            >
              <i class="pi pi-heart-fill" />{{ $t('teamHub.supporterRegistered') }}
            </span>
            <Button
              v-else
              :label="$t('teamHub.becomeSupporter')"
              icon="pi pi-heart"
              size="small"
              severity="secondary"
              outlined
              class="w-full"
              :loading="followingTeamIds.includes(team.slug)"
              @click="followTeam(team.slug, $event)"
            />
          </div>
        </div>
      </div>

      <div class="mt-6">
        <Paginator
          :rows="pageSize"
          :total-records="totalRecords"
          :first="currentPage * pageSize"
          @page="onPageChange"
        />
      </div>
    </template>

    <EntityCreateDialog
      entity-type="team"
      :visible="showCreateDialog"
      @update:visible="showCreateDialog = $event"
      @created="onTeamCreated"
    />
  </div>
</template>
