<script setup lang="ts">
import type { BulletinThreadResponse } from '~/types/bulletin'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgId = String(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

const selectedThread = ref<BulletinThreadResponse | null>(null)
const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

/** タブ: 'threads'=通常一覧 / 'archive'=保管庫ビュー。 */
const activeTab = ref<'threads' | 'archive'>('threads')

function onSaved() { listRef.value?.refresh() }

function onSwitchTab(tab: 'threads' | 'archive') {
  activeTab.value = tab
  selectedThread.value = null
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <PageHeader :title="t('bulletin.title')" />

    <div v-if="selectedThread" class="mx-auto max-w-3xl">
      <BulletinThreadDetail :thread-id="selectedThread.id" :can-manage="isAdminOrDeputy" @back="selectedThread = null" />
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
        scope-type="ORGANIZATION"
        :scope-id="orgId"
        :can-manage="isAdminOrDeputy"
        @select="(t) => selectedThread = t"
        @create="showCreateDialog = true"
      />

      <BulletinArchiveView
        v-else
        scope-type="ORGANIZATION"
        :scope-id="orgId"
        :can-manage="isAdminOrDeputy"
        @select="(t) => selectedThread = t"
      />
    </template>

    <BulletinThreadForm v-model:visible="showCreateDialog" scope-type="ORGANIZATION" :scope-id="orgId" @saved="onSaved" />
  </div>
</template>
