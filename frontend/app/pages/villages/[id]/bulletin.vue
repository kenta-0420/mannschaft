<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 掲示板タブ
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1 / §4.9 (scope=VILLAGE)
 *
 * 構成:
 *   - 上段: <VillageHeader> （FE2 完成版）
 *   - 下段: 掲示板一覧（Phase 2 で本格実装。本フェーズはプレースホルダー）
 *
 * VillageHeader emit:
 *   - join / leave / pin / unpin / report-click / edit
 *
 * 既存 BulletinThreadList は scopeType:'TEAM' | 'ORGANIZATION' のみ受付ける設計のため、
 * scope=VILLAGE 対応は Phase 2 で BulletinThreadList 側を拡張する。
 * 本ページでは Phase 2 への TODO コメントと一緒に簡易プレースホルダーを表示する。
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
/** 自分のメンバーシップ（退村時に id が必要） */
const myMembership = ref<MembershipResponse | null>(null)

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

/**
 * 通報ダイアログ表示要求。
 * Dialog 本体は FE5 担当 (<VillageReportDialog />) のため、
 * 本フェーズでは表示要求フラグのみ保持し、未存在 placeholder を render する。
 */
const showReportDialog = ref(false)
function onReportClick() {
  showReportDialog.value = true
}

function onEdit() {
  // 編集 Dialog は後続フェーズで実装予定。
  // 暫定として遷移先未確定のため console 出力のみとする。
  console.log('[village/bulletin] edit requested for village', villageId)
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
        <!--
          TODO (Phase 2):
            - BulletinThreadList の scopeType を 'VILLAGE' へ拡張する
              (frontend/app/types/bulletin.ts / components/bulletin/*)
            - 既存 useBulletinApi に scope=VILLAGE を渡せるよう拡張
            - BulletinThreadForm も同様に拡張
            - 投稿主体 (PostingIdentity) 連携で as TEAM/ORG 投稿を選択可能にする
        -->
        <DashboardEmptyState
          icon="pi pi-megaphone"
          :message="t('village.placeholder.bulletinComingSoon')"
        />
      </div>

      <!--
        通報ダイアログは FE5 (VillageReportDialog) で実装予定。
        コンポ未存在のため、本フェーズではプレースホルダー div で要求のみ記録する。
      -->
      <div
        v-if="showReportDialog"
        class="fixed inset-0 z-40 flex items-center justify-center bg-black/40"
        @click="showReportDialog = false"
      >
        <div
          class="rounded-lg bg-surface-0 p-6 shadow-xl"
          @click.stop
        >
          <p class="mb-2 font-semibold">
            {{ t('village.report.dialog.title') }}
          </p>
          <p class="text-sm text-surface-600">
            VillageReportDialog (FE5) is not yet implemented.
          </p>
          <div class="mt-4 text-right">
            <Button
              :label="t('village.action.cancel')"
              size="small"
              text
              @click="showReportDialog = false"
            />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
