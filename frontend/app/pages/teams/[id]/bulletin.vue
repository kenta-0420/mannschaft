<script setup lang="ts">
import type { BulletinThreadResponse } from '~/types/bulletin'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = String(route.params.id)
const { isAdminOrDeputy, isMember, loadPermissions } = useRoleAccess('team', teamId)
const teamStore = useTeamStore()

/** publicId (UUID) → BIGINT ID に解決する */
const numericTeamId = computed<number | undefined>(() =>
  teamStore.myTeams.find(t => t.publicId === teamId)?.id
)

const selectedThread = ref<BulletinThreadResponse | null>(null)
const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

/** タブ: 'threads'=通常一覧 / 'archive'=保管庫ビュー。 */
const activeTab = ref<'threads' | 'archive'>('threads')

function onSaved() {
  listRef.value?.refresh()
}

function onSwitchTab(tab: 'threads' | 'archive') {
  activeTab.value = tab
  selectedThread.value = null
}

onMounted(async () => {
  loadPermissions()
  // myTeams が未ロードの場合は取得して BIGINT ID を解決する
  if (!teamStore.myTeams.length) {
    await teamStore.fetchMyTeams().catch(() => {})
  }
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton :to="`/teams/${teamId}`" />
      <PageHeader :title="t('bulletin.title')" />
    </div>

    <!-- スレッド詳細表示中 -->
    <div v-if="selectedThread" class="mx-auto max-w-3xl">
      <BulletinThreadDetail
        :thread-id="selectedThread.id"
        :can-manage="isAdminOrDeputy"
        @back="selectedThread = null"
      />
    </div>

    <template v-else>
      <!-- numericTeamId が解決されるまでスケルトン表示 -->
      <div v-if="!numericTeamId" class="p-4 text-center text-surface-500">
        <i class="pi pi-spin pi-spinner mr-2" />{{ t('common.loading') }}
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
          :scope-id="numericTeamId"
          :can-manage="isAdminOrDeputy"
          :can-create="isMember"
          @select="(t) => selectedThread = t"
          @create="showCreateDialog = true"
        />

        <BulletinArchiveView
          v-else
          scope-type="TEAM"
          :scope-id="numericTeamId"
          :can-manage="isAdminOrDeputy"
          @select="(t) => selectedThread = t"
        />
      </template>
    </template>

    <BulletinThreadForm
      v-model:visible="showCreateDialog"
      scope-type="TEAM"
      :scope-id="numericTeamId ?? 0"
      @saved="onSaved"
    />
  </div>
</template>
