<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const orgStore = useOrganizationStore()
const foldersStore = useScopeFoldersStore()
const { handleApiError } = useErrorHandler()
const route = useRoute()
const router = useRouter()

const showCreateDialog = ref(false)

type ViewMode = 'grid' | 'list'
const VIEW_MODE_KEY = 'mannschaft:orgs:viewMode'

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

function onOrgCreated(entity: { id: string; name: string; slug: string }) {
  navigateTo(`/organizations/${entity.slug}`)
}

/** F15.3: URL クエリ `?folder=` がソース・オブ・トゥルース。 */
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
  // 重要データ（所属組織一覧）の取得失敗はユーザーに通知する。
  await orgStore.fetchMyOrganizations().catch((e) => handleApiError(e, '組織一覧取得'))
  try {
    await foldersStore.fetchAll('ORGANIZATION')
  }
  catch {
    // 取得失敗時もページ動作は阻害しない
  }
})

const filteredOrgs = computed(() => {
  const all = orgStore.myOrganizations
  const v = currentFolderId.value
  if (v === 'all' || v === 'manage') return all
  if (v === 'default') {
    const def = foldersStore.defaultFolderFor('ORGANIZATION')
    if (!def) return all
    const idSet = new Set(def.itemScopeIds)
    return all.filter(o => idSet.has(String(o.id)))
  }
  const folder = foldersStore.foldersFor('ORGANIZATION').find(f => f.id === v)
  if (!folder) return []
  const idSet = new Set(folder.itemScopeIds)
  return all.filter(o => idSet.has(String(o.id)))
})

const isManageView = computed(() => currentFolderId.value === 'manage')
</script>

<template>
  <div class="mx-auto max-w-6xl p-6">
    <!-- ヘッダー -->
    <div class="mb-6 flex flex-wrap items-center gap-3">
      <PageHeader :title="$t('orgHub.pageTitle')" class="flex-1" />
      <div class="flex items-center gap-2">
        <Button
          :label="$t('orgHub.createOrg')"
          icon="pi pi-plus"
          @click="showCreateDialog = true"
        />
        <Button
          :label="$t('orgHub.searchOrg')"
          icon="pi pi-search"
          severity="secondary"
          outlined
          @click="navigateTo('/organizations/search')"
        />
      </div>
    </div>

    <!-- 組織のお知らせ -->
    <SectionCard :title="$t('orgHub.announcements')" class="mb-6">
      <WidgetOrgAnnouncements :embedded="true" />
    </SectionCard>

    <!-- F15.3: フォルダタブ -->
    <div class="mb-4">
      <ScopeFolderTabs
        scope-type="ORGANIZATION"
        :current-folder-id="currentFolderId"
        @update:current-folder-id="setCurrentFolderId"
      />
    </div>

    <!-- 管理タブ: フォルダ管理 UI -->
    <div v-if="isManageView" class="mt-2">
      <ScopeFolderSection
        scope-type="ORGANIZATION"
        :items="orgStore.myOrganizations"
      />
    </div>

    <template v-else>
      <!-- 所属組織一覧 -->
      <div class="mb-4 flex items-center justify-between">
        <h2 class="text-lg font-semibold">{{ $t('orgHub.myOrgs') }}</h2>
        <div class="flex items-center gap-1">
          <Button
            icon="pi pi-th-large"
            :severity="viewMode === 'grid' ? 'primary' : 'secondary'"
            :outlined="viewMode !== 'grid'"
            size="small"
            :aria-label="$t('orgHub.viewGrid')"
            @click="viewMode = 'grid'"
          />
          <Button
            icon="pi pi-list"
            :severity="viewMode === 'list' ? 'primary' : 'secondary'"
            :outlined="viewMode !== 'list'"
            size="small"
            :aria-label="$t('orgHub.viewList')"
            @click="viewMode = 'list'"
          />
        </div>
      </div>

      <DashboardEmptyState
        v-if="filteredOrgs.length === 0"
        icon="pi pi-building"
        :message="$t('orgHub.noOrgs')"
      />

      <!-- グリッド表示 -->
      <div
        v-else-if="viewMode === 'grid'"
        class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
      >
        <div
          v-for="org in filteredOrgs"
          :key="org.id"
          class="cursor-pointer rounded-lg border-2 border-surface-400 bg-surface-0 p-4 transition-shadow hover:shadow-md"
          @click="org.slug ? navigateTo(`/organizations/${org.slug}`) : undefined"
        >
          <div class="mb-3 flex items-center gap-3">
            <Avatar
              :image="org.iconUrl ?? undefined"
              :label="org.iconUrl ? undefined : (org.nickname1 || org.name).charAt(0)"
              shape="circle"
              size="large"
            />
            <div class="min-w-0 flex-1">
              <h3 class="truncate font-semibold">
                {{ org.nickname1 || org.name }}
              </h3>
            </div>
          </div>
          <div class="flex items-center justify-end text-sm text-gray-500">
            <span>
              <i class="pi pi-users mr-1" />{{ $t('orgHub.memberCount', { count: org.memberCount }) }}
            </span>
          </div>
        </div>
      </div>

      <!-- リスト表示 -->
      <div v-else class="flex flex-col gap-2">
        <div
          v-for="org in filteredOrgs"
          :key="org.id"
          class="flex cursor-pointer items-center gap-4 rounded-lg border border-surface-200 bg-surface-0 px-4 py-3 transition-shadow hover:shadow-sm"
          @click="org.slug ? navigateTo(`/organizations/${org.slug}`) : undefined"
        >
          <Avatar
            :image="org.iconUrl ?? undefined"
            :label="org.iconUrl ? undefined : (org.nickname1 || org.name).charAt(0)"
            shape="circle"
            size="normal"
          />
          <div class="min-w-0 flex-1">
            <span class="truncate font-semibold">{{ org.nickname1 || org.name }}</span>
          </div>
          <span class="shrink-0 text-sm text-gray-500">
            <i class="pi pi-users mr-1" />{{ $t('orgHub.memberCount', { count: org.memberCount }) }}
          </span>
        </div>
      </div>
    </template>

    <EntityCreateDialog
      entity-type="organization"
      :visible="showCreateDialog"
      @update:visible="showCreateDialog = $event"
      @created="onOrgCreated"
    />
  </div>
</template>
