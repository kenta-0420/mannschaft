<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細「永続シェル」親レイアウトルート。
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1
 *
 * # 役割（永続シェル方式 / SPA）
 *  Nuxt 3 では `pages/villages/[id].vue` を置くと `pages/villages/[id]/*.vue` の親になる。
 *  本ファイルは村詳細の「常駐シェル」として以下を 1 度だけ解決し、子タブへ provide する:
 *    - 村データ（useAsyncData の `server: false` でクライアント専用取得・タブ遷移では再実行されない）
 *    - 自分のメンバーシップ（退村時に id が必要）
 *    - 権限フラグ（村長/長老/村人）
 *    - VillageHeader の常駐描画 + アクション emit ハンドラ（join/requestJoin/leave/
 *      pin/unpin/report-click/edit）の親集約
 *    - 村 notFound / archived（凍結）/ 読込エラーの一括ガード
 *    - `/villages/{id}` 直アクセス時の bulletin 既定リダイレクト
 *
 *  `<NuxtPage />` でタブ本体（パネル）だけを差し替えるため、タブ遷移時にヘッダは
 *  再マウントされず、白画面ローディングも出ない。
 *
 * # 注意
 *  - `key: route => route.fullPath` は **絶対に付けない**。付けると子遷移で親が
 *    再マウントし、永続シェルの設計が崩壊する。
 */
import type { MembershipResponse } from '~/types/village'
import type { VillageWithMonsho } from '~/composables/useVillageContext'
import { provideVillageContext } from '~/composables/useVillageContext'

// =============================================================================
// ルートメタ
//  - auth ミドルウェアは親で一括適用（子からは外す）。
//  - bulletin 既定リダイレクトは「ちょうど /villages/{id}」へ来たときだけ発火させる。
//    親ミドルウェアは子ルートにも継承されるため、パス末尾セグメントが id と一致する
//    （= タブ未指定）場合のみリダイレクトする。route 名の生成規則に依存しない判定。
// =============================================================================

definePageMeta({
  middleware: [
    'auth',
    (to) => {
      const id = String(to.params.id)
      const segments = to.path.replace(/\/+$/, '').split('/')
      const last = segments[segments.length - 1]
      // `/villages/{id}`（タブ未指定）の直アクセスのみ bulletin へ。
      // `/villages/{id}/bulletin` 等の子ルートでは last !== id なので何もしない。
      if (last === id) {
        return navigateTo(`/villages/${id}/bulletin`, { replace: true })
      }
    },
  ],
  layout: 'default',
})

const route = useRoute()
const { t } = useI18n()
const villageApi = useVillageApi()
const authStore = useAuthStore()
const { handleApiError } = useErrorHandler()

const villageId = computed<string>(() => String(route.params.id))

// =============================================================================
// 村データ — useAsyncData で 1 回だけ取得
//  - `server: false`: 本アプリの認証はクライアント専用（useApi の onRequest が
//    authStore.accessToken〔localStorage 由来〕がある時だけ Bearer を付与する）。
//    SSR では authStore が空＝無認証となり BE が 401 を返して村取得が失敗し、
//    親シェルがエラーフォールバックを表示してしまう（VillageHeader もタブも出ない）。
//    そのため村取得はクライアント側に寄せ、authStore が確定してから実行する。
//    旧子ページの onMounted 取得と同じ認証文脈になる。
//  - 初回は villageData=null かつ status='pending' になるため、テンプレートは
//    pending 中をローディング表示にして VillageHeader / 子(<NuxtPage>) を描画しない。
// =============================================================================

const {
  data: villageData,
  error: villageError,
  status: villageStatus,
  refresh: refreshVillage,
} = await useAsyncData(
  `village-${villageId.value}`,
  () => villageApi.getVillage(villageId.value),
  {
    // SSR では無認証 fetch になるためクライアント専用にする（上記コメント参照）。
    server: false,
    // 永続シェルを維持したまま別の村へ遷移した場合（同一親ルート record）に再取得する。
    watch: [villageId],
  },
)

/**
 * 村ロード中フラグ。
 *  `server: false` のため初回は status='pending'（クライアントで取得確定前）。
 *  この間は VillageHeader（village=null）や子パネルを描画せずローディングを出す。
 *  別の村へ遷移して再取得している間も pending=true になり、据置中の村でちらつかせない。
 */
const isVillageLoading = computed<boolean>(
  () => villageStatus.value === 'pending' || villageStatus.value === 'idle',
)

/** VillageHeader 用の交差型 Ref（村紋 r2Key を optional で許容）。 */
const village = computed<VillageWithMonsho | null>(() => villageData.value ?? null)

/** notFound 判定（404）。 */
const notFound = computed<boolean>(() => {
  const e = villageError.value as
    | { statusCode?: number, response?: { status?: number } }
    | null
  if (!e) return false
  const code = e.statusCode ?? e.response?.status
  return code === 404
})

/** 凍結（archived）判定。 */
const isArchived = computed<boolean>(() => !!village.value?.archivedAt)

// =============================================================================
// メンバーシップ — 親で 1 度取得（listMembers から自分の USER membership を解決）
// =============================================================================

const myMembership = ref<MembershipResponse | null>(null)

const currentUserId = computed<number | null>(() => authStore.currentUser?.id ?? null)

async function loadMyMembership() {
  const myUserId = currentUserId.value
  if (!myUserId || !village.value?.isMember) {
    myMembership.value = null
    return
  }
  try {
    const res = await villageApi.listMembers(villageId.value, { page: 0, size: 100 })
    myMembership.value = res.content.find(
      (m: MembershipResponse) => m.subjectType === 'USER' && m.subjectId === myUserId,
    ) ?? null
  }
  catch (error) {
    // 退村用 id 取得失敗はサイレントでよい（leave 押下時に再ロード）
    console.warn('[villages/[id]] listMembers failed', error)
    myMembership.value = null
  }
}

// クライアントで村取得後にメンバーシップを解決（SSR では認証ストア未確定のためスキップ）
onMounted(() => {
  void loadMyMembership()
})

// 永続シェルを維持したまま別の村へ遷移した場合にメンバーシップも再解決する。
watch(villageId, () => {
  void loadMyMembership()
})

/** 村再取得 + メンバーシップ再解決（join/leave/pin 後の状態同期）。 */
async function refresh() {
  await refreshVillage()
  await loadMyMembership()
}

// =============================================================================
// 権限フラグ
// =============================================================================

const perms = computed(() => {
  const role = village.value?.myRole ?? null
  return {
    isMember: !!village.value?.isMember,
    isAdmin: role === 'HEADMAN' || role === 'ELDER',
    isHeadman: role === 'HEADMAN',
    myRole: role,
  }
})

// =============================================================================
// アクティブタブ導出（ルート末尾セグメント → VillageHeader の activeTab キー）
// =============================================================================

type HeaderTab =
  | 'bulletin'
  | 'timeline'
  | 'lobby'
  | 'members'
  | 'calendar'
  | 'festival'
  | 'matchRecruit'
  | 'meetup'
  | 'chronicle'

/** ルート末尾セグメント（kebab）→ VillageHeader の activeTab キー。 */
const SEGMENT_TO_TAB: Record<string, HeaderTab> = {
  bulletin: 'bulletin',
  timeline: 'timeline',
  lobby: 'lobby',
  members: 'members',
  calendar: 'calendar',
  festivals: 'festival',
  'match-recruits': 'matchRecruit',
  meetups: 'meetup',
  chronicles: 'chronicle',
}

const activeTab = computed<HeaderTab>(() => {
  const segments = route.path.replace(/\/+$/, '').split('/')
  const last = segments[segments.length - 1] ?? ''
  // 未知セグメント（newsletter-settings 等）は bulletin をハイライト既定にする
  return SEGMENT_TO_TAB[last] ?? 'bulletin'
})

// =============================================================================
// VillageHeader アクションハンドラ（親集約）
// =============================================================================

async function onJoin() {
  const myUserId = currentUserId.value
  if (!myUserId) return
  try {
    await villageApi.joinVillage(villageId.value, {
      subjectType: 'USER',
      subjectId: myUserId,
    })
    await refresh()
  }
  catch (error) {
    handleApiError(error, t('village.action.join'))
  }
}

/** 承認制（APPROVAL）村: 参加申請画面へ遷移（members.vue の仕様を正本に）。 */
function onRequestJoin() {
  void navigateTo(`/villages/${villageId.value}/join-request`)
}

async function onLeave() {
  if (!myMembership.value) {
    await loadMyMembership()
  }
  if (!myMembership.value) {
    // 既に非メンバー等。状態を再同期して終了
    await refresh()
    return
  }
  try {
    await villageApi.leaveVillage(villageId.value, myMembership.value.id)
    await refresh()
  }
  catch (error) {
    handleApiError(error, t('village.action.leave'))
  }
}

async function onPin() {
  try {
    await villageApi.addPin(villageId.value)
    await refresh()
  }
  catch (error) {
    handleApiError(error, t('village.action.pin'))
  }
}

async function onUnpin() {
  try {
    await villageApi.removePin(villageId.value)
    await refresh()
  }
  catch (error) {
    handleApiError(error, t('village.action.unpin'))
  }
}

/** 通報ダイアログ（村本体対象）。 */
const showReportDialog = ref(false)
function onReportClick() {
  showReportDialog.value = true
}

/** 村本体編集ダイアログ（村長のみ・VillageHeader 側で表示制御）。 */
const showEditDialog = ref(false)
function onEdit() {
  showEditDialog.value = true
}

/** 使い方モーダル表示制御。 */
const showGuide = ref(false)

/** 編集 Dialog から更新成功時に村情報を差し替え、子へも反映。 */
function onVillageUpdated(updated: VillageWithMonsho) {
  villageData.value = updated
}

// =============================================================================
// 子タブへ provide
// =============================================================================

provideVillageContext({
  village,
  refresh,
  myMembership,
  perms,
  currentUserId,
  openEditDialog: onEdit,
})
</script>

<template>
  <div>
    <!--
      村ロード中（server:false のため初回は必ずここ）。
      authStore 確定後にクライアントで村を取得する。取得完了までは VillageHeader
      （village=null）も子パネル（<NuxtPage>）も描画しない＝村 context 未解決での
      子描画を防ぐ。タブ遷移時は村が据置のため pending にならずローディングは出ない。
    -->
    <PageLoading v-if="isVillageLoading" />

    <!-- 村が見つからない（404） -->
    <div v-else-if="notFound" class="mx-auto max-w-2xl p-6 text-center">
      <i class="pi pi-exclamation-circle text-4xl text-surface-400" aria-hidden="true" />
      <p class="mt-4 text-lg">
        {{ t('village.error.VILLAGE_001') }}
      </p>
      <NuxtLink to="/villages" class="mt-4 inline-block text-primary-600 hover:underline">
        <i class="pi pi-arrow-left mr-1" />
        {{ t('village.error.backToList') }}
      </NuxtLink>
    </div>

    <!-- 読み込みエラー（非 404） -->
    <div
      v-else-if="villageError && !notFound"
      class="mx-auto max-w-2xl p-6 text-center"
    >
      <i class="pi pi-exclamation-triangle text-4xl text-surface-400" aria-hidden="true" />
      <p class="mt-4 text-lg text-surface-700 dark:text-surface-200">
        {{ t('village.error.generic') }}
      </p>
      <div class="mt-4 flex justify-center gap-3">
        <Button
          :label="t('village.feed.retry')"
          icon="pi pi-refresh"
          @click="refreshVillage()"
        />
        <NuxtLink to="/villages">
          <Button
            :label="t('village.error.backToList')"
            severity="secondary"
          />
        </NuxtLink>
      </div>
    </div>

    <!-- 通常表示（村取得済み） -->
    <template v-else-if="village">
      <VillageHeader
        :village="village"
        :active-tab="activeTab"
        @join="onJoin"
        @request-join="onRequestJoin"
        @leave="onLeave"
        @pin="onPin"
        @unpin="onUnpin"
        @report-click="onReportClick"
        @edit="onEdit"
        @help="showGuide = true"
      />

      <!-- 凍結（archived）案内: 閲覧のみ可能 -->
      <div
        v-if="isArchived"
        class="mx-auto max-w-3xl px-4 sm:px-6 pt-3"
      >
        <Message severity="warn" :closable="false">
          {{ t('village.archivedNotice') }}
        </Message>
      </div>

      <!-- タブ本体（子）— 永続シェル下で差し替え -->
      <NuxtPage />

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

      <!-- 使い方モーダル -->
      <VillageGuideModal v-model:visible="showGuide" />
    </template>

    <!--
      到達想定外フォールバック（pending でも error でも village 取得済みでもない）。
      村が見つからない扱いにして無限ローディングや VillageHeader(null) 描画を防ぐ。
    -->
    <div v-else class="mx-auto max-w-2xl p-6 text-center">
      <i class="pi pi-exclamation-circle text-4xl text-surface-400" aria-hidden="true" />
      <p class="mt-4 text-lg">
        {{ t('village.error.VILLAGE_001') }}
      </p>
      <NuxtLink to="/villages" class="mt-4 inline-block text-primary-600 hover:underline">
        <i class="pi pi-arrow-left mr-1" />
        {{ t('village.error.backToList') }}
      </NuxtLink>
    </div>
  </div>
</template>
