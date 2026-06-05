<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 掲示板タブ（Phase 2 本実装）
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1 / §4.9 (scope=VILLAGE)
 *
 * 構成:
 *   - 上段: <VillageHeader>
 *   - 下段: 掲示板スレッド一覧（BulletinThreadList + BulletinThreadForm）
 *
 * Phase 2 変更点:
 *   - BulletinScopeType に 'VILLAGE' を追加
 *   - BulletinThreadList.scopeId を string | number に拡張（UUID 対応）
 *   - VILLAGE スコープでは保管庫タブを非表示（バックエンド未対応）
 */
import type { BulletinThreadResponse } from '~/types/bulletin'
import type { MembershipResponse, VillageResponse } from '~/types/village'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
  key: route => route.fullPath,
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
/** 自分のメンバーシップ（退村時に id が必要） */
const myMembership = ref<MembershipResponse | null>(null)

// =====================================================================
// 掲示板 State（Phase 2 追加）
// =====================================================================

/** 選択中のスレッド（詳細表示） */
const selectedThread = ref<BulletinThreadResponse | null>(null)
/** スレッド作成ダイアログ表示フラグ */
const showCreateDialog = ref(false)
/** BulletinThreadList の ref（スレッド保存後にリフレッシュするため） */
const listRef = ref<{ refresh: () => void } | null>(null)

/** 村長（HEADMAN）または長老（ELDER）の場合は管理権限あり */
const isAdmin = computed(() =>
  village.value?.myRole === 'HEADMAN' || village.value?.myRole === 'ELDER',
)

function onSaved() {
  listRef.value?.refresh()
}

// =====================================================================
// Fetch
// =====================================================================

async function loadVillage() {
  loading.value = true
  notFound.value = false
  try {
    village.value = await villageApi.getVillage(villageId)
    // 自分がメンバーの場合のみ、退村用に membership を取りに行く
    if (village.value?.isMember) {
      await loadMyMembership()
    }
    else {
      myMembership.value = null
    }
  }
  catch (error: unknown) {
    // 404 は notFound 表示、それ以外はトースト
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

/**
 * 自分のメンバーシップ (subjectType=USER, subjectId=currentUser.id) を引き当てる。
 * 退村 API は membershipId を要求するため、leave 時にこの id を使う。
 */
async function loadMyMembership() {
  const myUserId = authStore.currentUser?.id
  if (!myUserId) {
    myMembership.value = null
    return
  }
  try {
    // 大量メンバー想定で page 0 から探す。USER subject の自分は 1 件
    const res = await villageApi.listMembers(villageId, { page: 0, size: 100 })
    myMembership.value = res.content.find(
      (m: MembershipResponse) => m.subjectType === 'USER' && m.subjectId === myUserId,
    ) ?? null
  }
  catch (error) {
    // 退村用 id 取得失敗はサイレントでよい（leave 押下時に再ロード）
    console.warn('[village/bulletin] listMembers failed', error)
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
  // membership が未取得なら一度だけ取り直し
  if (!myMembership.value) {
    await loadMyMembership()
  }
  if (!myMembership.value) {
    // それでも取れない = 既に非メンバー等。状態を再同期して終了
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
        active-tab="bulletin"
        @join="onJoin"
        @request-join="onJoin"
        @leave="onLeave"
        @pin="onPin"
        @unpin="onUnpin"
        @report-click="onReportClick"
        @edit="onEdit"
      />

      <!-- 掲示板本体 -->
      <div class="mx-auto max-w-3xl p-4 sm:p-6">
        <!-- スレッド詳細（選択時） -->
        <div v-if="selectedThread" class="mx-auto max-w-3xl">
          <BulletinThreadDetail
            :thread-id="selectedThread.id"
            :can-manage="isAdmin"
            @back="selectedThread = null"
          />
        </div>

        <!-- スレッド一覧 -->
        <template v-else>
          <BulletinThreadList
            ref="listRef"
            scope-type="VILLAGE"
            :scope-id="villageId"
            :can-manage="isAdmin"
            @select="(thread) => selectedThread = thread"
            @create="showCreateDialog = true"
          />
        </template>

        <!-- ご縁ランキング (Phase 3) — 村人のみ表示 -->
        <section v-if="village.isMember && !selectedThread" class="mt-6 rounded-lg border border-surface-200 p-4 dark:border-surface-700">
          <VillageSerendipityRankingWidget :village-id="village.id" />
        </section>
      </div>

      <!-- スレッド作成ダイアログ -->
      <BulletinThreadForm
        v-model:visible="showCreateDialog"
        scope-type="VILLAGE"
        :scope-id="villageId"
        @saved="onSaved"
      />

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

    <!-- 読み込みエラー（非 404）: village=null かつ notFound=false の状態 -->
    <div v-else class="mx-auto max-w-2xl p-6 text-center">
      <i class="pi pi-exclamation-triangle text-4xl text-surface-400" aria-hidden="true" />
      <p class="mt-4 text-lg text-surface-700 dark:text-surface-200">
        {{ t('village.error.generic') }}
      </p>
      <div class="mt-4 flex justify-center gap-3">
        <Button
          :label="t('village.feed.retry')"
          icon="pi pi-refresh"
          @click="loadVillage()"
        />
        <NuxtLink to="/villages">
          <Button
            :label="t('village.error.backToList')"
            severity="secondary"
          />
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
