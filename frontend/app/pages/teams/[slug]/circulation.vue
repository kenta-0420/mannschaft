<script setup lang="ts">
import type { CirculationDocumentListItem } from '~/types/circulation'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const loading = ref(true)
// 権限取得が settle したが失敗した（{ok:false} / 例外）状態。無言で権限なしに倒さず、
// エラー面＋再試行で可視化する（症状を隠さない・根治治療の原則）。
const permissionsError = ref(false)
// 15 秒 watchdog: settle しない場合にスピナーの下へ「時間がかかっています」＋再試行を追加表示する。
// 読み込み自体は継続（loading は解除しない）。
const slowLoading = ref(false)
const WATCHDOG_MS = 15_000
let watchdogTimer: ReturnType<typeof setTimeout> | null = null

// 一覧コンポーネントの参照（押印後の一覧再取得に使う）。
const listRef = ref<{ refresh: () => void } | null>(null)

// 詳細モーダルの状態。
const detailVisible = ref(false)
const selectedDocumentId = ref<number | null>(null)
// 詳細 EP（/teams/{teamId}/circulations/{documentId}）は数値 teamId を要求するため、
// 選択 item の scopeId（数値チーム ID）を渡す。表示名解決は slug でメンバー一覧を引く。
const selectedTeamId = ref<string>(teamSlug)

function onSelect(item: CirculationDocumentListItem) {
  selectedDocumentId.value = item.id
  selectedTeamId.value = String(item.scopeId)
  detailVisible.value = true
}

// 作成モーダルの状態。
const createVisible = ref(false)

function onCreate() {
  createVisible.value = true
}

// 作成 + 開始が完了したら一覧を再取得する（新しい回覧が「回覧中」で出る）。
function onCreated() {
  listRef.value?.refresh()
}

function onStamped() {
  listRef.value?.refresh()
}

function clearWatchdog() {
  if (watchdogTimer !== null) {
    clearTimeout(watchdogTimer)
    watchdogTimer = null
  }
}

/**
 * 権限を取得してページ門番を解除する。取得失敗はエラー面で可視化し、再試行を可能にする。
 * loadPermissions() は throw しない設計だが、将来の例外にも備えて try/catch で受ける。
 */
async function loadPage() {
  loading.value = true
  permissionsError.value = false
  slowLoading.value = false
  clearWatchdog()
  watchdogTimer = setTimeout(() => {
    if (loading.value) slowLoading.value = true
  }, WATCHDOG_MS)
  try {
    const { ok } = await loadPermissions()
    permissionsError.value = !ok
  }
  catch {
    permissionsError.value = true
  }
  finally {
    loading.value = false
    slowLoading.value = false
    clearWatchdog()
  }
}

onMounted(loadPage)
onBeforeUnmount(clearWatchdog)
</script>

<template>
  <!-- 読み込み中（15s watchdog で「時間がかかっています」＋再試行を追加表示） -->
  <div v-if="loading">
    <PageLoading />
    <div v-if="slowLoading" class="flex flex-col items-center gap-3 pb-8 text-center">
      <p class="text-sm text-surface-500">{{ t('common.scopeShell.slow_loading') }}</p>
      <Button
        :label="t('common.scopeShell.retry')"
        icon="pi pi-refresh"
        size="small"
        outlined
        @click="loadPage"
      />
    </div>
  </div>

  <!-- 権限取得失敗のエラー面（無言で権限なしに倒さない・再試行で再フェッチ） -->
  <div v-else-if="permissionsError" class="mx-auto max-w-2xl p-6 text-center">
    <i class="pi pi-exclamation-triangle text-4xl text-surface-400" aria-hidden="true" />
    <p class="mt-4 text-lg font-medium text-surface-700 dark:text-surface-200">
      {{ t('common.scopeShell.load_error_title') }}
    </p>
    <p class="mt-1 text-sm text-surface-500">
      {{ t('common.scopeShell.load_error_body') }}
    </p>
    <Button
      class="mt-4"
      :label="t('common.scopeShell.retry')"
      icon="pi pi-refresh"
      @click="loadPage"
    />
  </div>

  <!-- 成功パス（従来どおり） -->
  <div v-else>
    <div class="mb-4 flex items-center gap-3">
      <PageHeader title="回覧板" />
    </div>
    <CirculationList
      ref="listRef"
      scope-type="TEAM"
      :scope-id="teamSlug"
      :can-manage="isAdminOrDeputy"
      @select="onSelect"
      @create="onCreate"
    />
    <CirculationDetailModal
      v-model:visible="detailVisible"
      :document-id="selectedDocumentId"
      :team-id="selectedTeamId"
      :team-slug="teamSlug"
      @stamped="onStamped"
    />
    <CirculationCreateModal
      v-model:visible="createVisible"
      :team-slug="teamSlug"
      @created="onCreated"
    />
  </div>
</template>
