<script setup lang="ts">
import type { BulletinThreadResponse } from '~/types/bulletin'
import { parseAnnouncementRouteId } from '~/utils/announcementRoute'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdminOrDeputy, isMember, loadPermissions } = useRoleAccess('team', teamSlug)

const selectedThread = ref<BulletinThreadResponse | null>(null)
const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)
const { getScopedThread } = useBulletinApi()
const { getTeam } = useTeamApi()
const { handleApiError } = useErrorHandler()
const linkLoading = ref(false)
const linkError = shallowRef<unknown>(null)
let linkSequence = 0

/** 元リンクのthreadIdはこのscopeの詳細APIで認可してから開く。 */
async function loadLinkedThread(): Promise<void> {
  const request = ++linkSequence
  selectedThread.value = null
  linkError.value = null
  linkLoading.value = true
  try {
    const threadId = parseAnnouncementRouteId(route.query.threadId)
    if (threadId === undefined) return
    const scope = await getTeam(teamSlug)
    const result = await getScopedThread('teams', String(scope.data.id), threadId)
    if (result.data.id !== threadId || result.data.scopeType !== 'TEAM' || String(result.data.scopeId) !== String(scope.data.id)) {
      throw new Error('Bulletin thread does not match its route scope')
    }
    if (request === linkSequence) selectedThread.value = result.data
  }
  catch (error) {
    if (request !== linkSequence) return
    linkError.value = error
    handleApiError(error)
  }
  finally {
    if (request === linkSequence) linkLoading.value = false
  }
}
watch(() => route.query.threadId, () => { void loadLinkedThread() })
onScopeDispose(() => { ++linkSequence })

/** タブ: 'threads'=通常一覧 / 'archive'=保管庫ビュー。 */
const activeTab = ref<'threads' | 'archive'>('threads')

function onSaved() {
  listRef.value?.refresh()
}

function onSwitchTab(tab: 'threads' | 'archive') {
  activeTab.value = tab
  selectedThread.value = null
}

onMounted(async () => { await loadPermissions(); await loadLinkedThread() })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <PageHeader :title="t('bulletin.title')" :back-to="`/teams/${teamSlug}`" />
    </div>

    <!-- スレッド詳細表示中 -->
    <PageLoading v-if="linkLoading" />
    <DashboardErrorState v-else-if="linkError" :error="linkError" @retry="loadLinkedThread" />
    <div v-else-if="selectedThread" class="mx-auto max-w-3xl">
      <BulletinThreadDetail
        :thread-id="selectedThread.id"
        :can-manage="isAdminOrDeputy"
        @back="selectedThread = null"
      />
    </div>

    <template v-else>
      <!-- 一覧 / 保管庫 タブ切替 -->
      <div class="mb-4 flex gap-2 border-b border-surface-200 dark:border-surface-700">
        <button
          type="button"
          class="-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors"
          :class="activeTab === 'threads' ? 'border-primary text-primary' : 'border-transparent text-surface-500 hover:text-surface-700'"
          @click="onSwitchTab('threads')"
        >
          <i class="pi pi-list mr-1" />{{ t('bulletin.tab.threads') }}
        </button>
        <button
          type="button"
          class="-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors"
          :class="activeTab === 'archive' ? 'border-primary text-primary' : 'border-transparent text-surface-500 hover:text-surface-700'"
          @click="onSwitchTab('archive')"
        >
          <i class="pi pi-inbox mr-1" />{{ t('bulletin.tab.archive') }}
        </button>
      </div>

      <BulletinThreadList
        v-if="activeTab === 'threads'"
        ref="listRef"
        scope-type="TEAM"
        :scope-id="teamSlug"
        :can-manage="isAdminOrDeputy"
        :can-create="isMember"
        @select="(t) => selectedThread = t"
        @create="showCreateDialog = true"
      />

      <BulletinArchiveView
        v-else
        scope-type="TEAM"
        :scope-id="teamSlug"
        :can-manage="isAdminOrDeputy"
        @select="(t) => selectedThread = t"
      />
    </template>

    <BulletinThreadForm
      v-model:visible="showCreateDialog"
      scope-type="TEAM"
      :scope-id="teamSlug"
      @saved="onSaved"
    />
  </div>
</template>
