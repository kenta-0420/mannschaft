<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / タイムラインタブ
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1 / §4.9 (scope=VILLAGE)
 *
 * 構成:
 *   - 上段: <VillageHeader> （FE2 完成版）
 *   - 下段: タイムライン一覧（Phase 2 で本格実装。本フェーズはプレースホルダー）
 *
 * 既存 TimelineFeed / TimelinePostForm は scopeType:'TEAM' | 'ORGANIZATION' | 'PUBLIC' のみ
 * 受付ける設計のため、scope=VILLAGE 対応は Phase 2 で拡張する。
 */
import type { MembershipResponse, VillageResponse } from '~/types/village'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const villageId = String(route.params.id)
const { t } = useI18n()
const villageApi = useVillageApi()
const authStore = useAuthStore()
const { handleApiError } = useErrorHandler()

// =====================================================================
// State
// =====================================================================

const village = ref<VillageResponse | null>(null)
const loading = ref(true)
const notFound = ref(false)
const myMembership = ref<MembershipResponse | null>(null)

// =====================================================================
// Fetch
// =====================================================================

async function loadVillage() {
  loading.value = true
  notFound.value = false
  try {
    village.value = await villageApi.getVillage(villageId)
    if (village.value?.isMember) {
      await loadMyMembership()
    }
    else {
      myMembership.value = null
    }
  }
  catch (error: unknown) {
    const status = (error as { statusCode?: number; response?: { status?: number } })
    const code = status?.statusCode ?? status?.response?.status
    if (code === 404) {
      notFound.value = true
    }
    else {
      handleApiError(error, t('village.title'))
    }
  }
  finally {
    loading.value = false
  }
}

async function loadMyMembership() {
  const myUserId = authStore.currentUser?.id
  if (!myUserId) {
    myMembership.value = null
    return
  }
  try {
    const res = await villageApi.listMembers(villageId, { page: 0, size: 100 })
    myMembership.value = res.content.find(
      (m: MembershipResponse) => m.subjectType === 'USER' && m.subjectId === myUserId,
    ) ?? null
  }
  catch (error) {
    console.warn('[village/timeline] listMembers failed', error)
    myMembership.value = null
  }
}

// =====================================================================
// VillageHeader アクションハンドラ
// =====================================================================

async function onJoin() {
  const myUserId = authStore.currentUser?.id
  if (!myUserId) return
  try {
    await villageApi.joinVillage(villageId, {
      subjectType: 'USER',
      subjectId: myUserId,
    })
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.join'))
  }
}

async function onLeave() {
  if (!myMembership.value) {
    await loadMyMembership()
  }
  if (!myMembership.value) {
    await loadVillage()
    return
  }
  try {
    await villageApi.leaveVillage(villageId, myMembership.value.id)
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.leave'))
  }
}

async function onPin() {
  try {
    await villageApi.addPin(villageId)
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.pin'))
  }
}

async function onUnpin() {
  try {
    await villageApi.removePin(villageId)
    await loadVillage()
  }
  catch (error) {
    handleApiError(error, t('village.action.unpin'))
  }
}

/** 通報ダイアログ表示状態 — VillageReportDialog (FE5 完成済) を組み込む */
const showReportDialog = ref(false)
function onReportClick() {
  showReportDialog.value = true
}

/** 編集ダイアログ表示状態 — VillageEditDialog (FE α2 で新規実装) を組み込む */
const showEditDialog = ref(false)
function onEdit() {
  showEditDialog.value = true
}

/** 編集 Dialog から更新成功時に村情報を差し替え */
function onVillageUpdated(updated: VillageResponse) {
  village.value = updated
}

// =====================================================================
// Init
// =====================================================================

onMounted(() => {
  loadVillage()
})
</script>

<template>
  <div>
    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- 村が見つからない -->
    <div v-else-if="notFound" class="mx-auto max-w-2xl p-6 text-center">
      <i class="pi pi-exclamation-circle text-4xl text-surface-400" />
      <p class="mt-4 text-lg">
        {{ t('village.error.VILLAGE_001') }}
      </p>
      <NuxtLink to="/villages" class="mt-4 inline-block text-primary-600 hover:underline">
        <i class="pi pi-arrow-left mr-1" />
        {{ t('village.error.backToList') }}
      </NuxtLink>
    </div>

    <!-- 通常表示 -->
    <template v-else-if="village">
      <VillageHeader
        :village="village"
        active-tab="timeline"
        @join="onJoin"
        @request-join="onJoin"
        @leave="onLeave"
        @pin="onPin"
        @unpin="onUnpin"
        @report-click="onReportClick"
        @edit="onEdit"
      />

      <!-- タイムライン本体 -->
      <div class="mx-auto max-w-2xl p-4 sm:p-6">
        <!--
          TODO (Phase 2):
            - TimelineFeed / TimelinePostForm の scopeType を 'VILLAGE' へ拡張する
              (frontend/app/types/timeline.ts / components/timeline/*)
            - 既存 useTimelineApi に scope=VILLAGE を渡せるよう拡張
            - 投稿主体 (PostingIdentity) 連携で as TEAM/ORG 投稿を選択可能にする
            - 投稿時の村スコープ可視性制御を適用
        -->
        <DashboardEmptyState
          icon="pi pi-clock"
          :message="t('village.placeholder.timelineComingSoon')"
        />
      </div>

      <!-- 通報ダイアログ — 対象は村本体 (VILLAGE) -->
      <VillageReportDialog
        v-model:visible="showReportDialog"
        :village-id="village.id"
        target-type="VILLAGE"
        :target-ref-id="village.id"
      />

      <!-- 村本体編集ダイアログ — 村長のみ（VillageHeader 側で制御） -->
      <VillageEditDialog
        v-model:visible="showEditDialog"
        :village="village"
        @updated="onVillageUpdated"
      />
    </template>
  </div>
</template>
