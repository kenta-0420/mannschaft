<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const teamStore = useTeamStore()
const foldersStore = useScopeFoldersStore()
const { templateLabel } = useScopeLabels()
const { handleApiError } = useErrorHandler()
const route = useRoute()
const router = useRouter()

const showCreateDialog = ref(false)

type ViewMode = 'grid' | 'list'
const VIEW_MODE_KEY = 'mannschaft:teams:viewMode'

function loadViewMode(): ViewMode {
  if (import.meta.client) {
    const saved = localStorage.getItem(VIEW_MODE_KEY)
    if (saved === 'grid' || saved === 'list') return saved
  }
  return 'grid'
}

const viewMode = ref<ViewMode>(loadViewMode())

watch(viewMode, (mode) => {
  if (import.meta.client) {
    localStorage.setItem(VIEW_MODE_KEY, mode)
  }
})

function onTeamCreated(entity: { id: string; name: string; slug: string }) {
  navigateTo(`/teams/${entity.slug}`)
}

/**
 * F15.3: URL クエリ `?folder=` がソース・オブ・トゥルース。
 *  - `all` / 未指定: すべて表示
 *  - `default`: 未分類のみ
 *  - `manage`: 管理タブ
 *  - 数値: 該当フォルダのみ
 */
type CurrentFolder = 'all' | 'default' | 'manage' | number

function parseFolderQuery(value: unknown): CurrentFolder {
  if (typeof value !== 'string') return 'all'
  if (value === 'default' || value === 'manage' || value === 'all') return value
  const n = Number(value)
  return Number.isFinite(n) ? n : 'all'
}

const currentFolderId = computed<CurrentFolder>(() =>
  parseFolderQuery(route.query.folder),
)

function setCurrentFolderId(value: CurrentFolder) {
  const next: Record<string, string> = { ...route.query as Record<string, string> }
  if (value === 'all') {
    delete next.folder
  }
  else {
    next.folder = String(value)
  }
  router.replace({ path: route.path, query: next })
}

onMounted(async () => {
  // 直接アクセス時もチーム一覧が表示されるよう常にフェッチする。
  // 重要データ（所属チーム一覧）の取得失敗はユーザーに通知する。
  await teamStore.fetchMyTeams().catch((e) => handleApiError(e, 'チーム一覧取得'))
  try {
    await foldersStore.fetchAll('TEAM')
  }
  catch {
    // 取得失敗時もページ動作は阻害しない
  }
})

/** フォルダフィルタを適用した表示用チーム一覧。 */
const filteredTeams = computed(() => {
  const all = teamStore.myTeams
  const v = currentFolderId.value
  if (v === 'all' || v === 'manage') return all
  if (v === 'default') {
    const def = foldersStore.defaultFolderFor('TEAM')
    if (!def) return all // 未分類フォルダ未作成なら全件
    const idSet = new Set(def.itemScopeIds)
    return all.filter(t => idSet.has(String(t.id)))
  }
  // numeric folder id
  const folder = foldersStore.foldersFor('TEAM').find(f => f.id === v)
  if (!folder) return []
  const idSet = new Set(folder.itemScopeIds)
  return all.filter(t => idSet.has(String(t.id)))
})

const isManageView = computed(() => currentFolderId.value === 'manage')
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <!-- ヘッダー -->
    <div class="mb-6 flex flex-wrap items-center gap-3">
      <PageHeader :title="$t('teamHub.pageTitle')" class="flex-1" :back="false" />
      <div class="flex items-center gap-2">
        <Button
          :label="$t('teamHub.createTeam')"
          icon="pi pi-plus"
          @click="showCreateDialog = true"
        />
        <Button
          :label="$t('teamHub.searchTeam')"
          icon="pi pi-search"
          severity="secondary"
          outlined
          @click="navigateTo('/teams/search')"
        />
      </div>
    </div>

    <!-- チームのお知らせ -->
    <SectionCard :title="$t('teamHub.announcements')" class="mb-6">
      <WidgetTeamAnnouncements :embedded="true" />
    </SectionCard>

    <!-- F15.3: フォルダタブ -->
    <div class="mb-4">
      <ScopeFolderScopeFolderTabs
        scope-type="TEAM"
        :current-folder-id="currentFolderId"
        @update:current-folder-id="setCurrentFolderId"
      />
    </div>

    <!-- 管理タブ: フォルダ管理 UI（ScopeFolderSection） -->
    <div v-if="isManageView" class="mt-2">
      <ScopeFolderSection
        scope-type="TEAM"
        :items="teamStore.myTeams"
      />
    </div>

    <template v-else>
      <!-- 所属チーム一覧 -->
      <div class="mb-4 flex items-center justify-between">
        <h2 class="text-lg font-semibold">{{ $t('teamHub.myTeams') }}</h2>
        <div class="flex items-center gap-1">
          <Button
            icon="pi pi-th-large"
            :severity="viewMode === 'grid' ? 'primary' : 'secondary'"
            :outlined="viewMode !== 'grid'"
            size="small"
            :aria-label="$t('teamHub.viewGrid')"
            @click="viewMode = 'grid'"
          />
          <Button
            icon="pi pi-list"
            :severity="viewMode === 'list' ? 'primary' : 'secondary'"
            :outlined="viewMode !== 'list'"
            size="small"
            :aria-label="$t('teamHub.viewList')"
            @click="viewMode = 'list'"
          />
        </div>
      </div>

      <DashboardEmptyState
        v-if="filteredTeams.length === 0"
        icon="pi pi-users"
        :message="$t('teamHub.noTeams')"
      />

      <!-- グリッド表示 -->
      <div
        v-else-if="viewMode === 'grid'"
        class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
      >
        <div
          v-for="team in filteredTeams"
          :key="team.id"
          class="cursor-pointer rounded-lg border-2 border-surface-400 bg-surface-0 p-4 transition-shadow hover:shadow-md"
          @click="team.slug ? navigateTo(`/teams/${team.slug}`) : undefined"
        >
          <div class="mb-3 flex items-center gap-3">
            <Avatar
              :image="team.iconUrl ?? undefined"
              :label="team.iconUrl ? undefined : (team.nickname1 || team.name).charAt(0)"
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
          <div class="flex items-center justify-end text-sm text-gray-500">
            <span>
              <i class="pi pi-users mr-1" />{{ $t('teamHub.memberCount', { count: team.memberCount }) }}
            </span>
          </div>
        </div>
      </div>

      <!-- リスト表示 -->
      <div v-else class="flex flex-col gap-2">
        <div
          v-for="team in filteredTeams"
          :key="team.id"
          class="flex cursor-pointer items-center gap-4 rounded-lg border border-surface-200 bg-surface-0 px-4 py-3 transition-shadow hover:shadow-sm"
          @click="team.slug ? navigateTo(`/teams/${team.slug}`) : undefined"
        >
          <Avatar
            :image="team.iconUrl ?? undefined"
            :label="team.iconUrl ? undefined : (team.nickname1 || team.name).charAt(0)"
            shape="circle"
            size="normal"
          />
          <div class="min-w-0 flex-1">
            <span class="truncate font-semibold">{{ team.nickname1 || team.name }}</span>
            <Tag
              :value="templateLabel[team.template] ?? team.template"
              severity="info"
              class="ml-2 text-xs"
            />
          </div>
          <span class="shrink-0 text-sm text-gray-500">
            <i class="pi pi-users mr-1" />{{ $t('teamHub.memberCount', { count: team.memberCount }) }}
          </span>
        </div>
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
