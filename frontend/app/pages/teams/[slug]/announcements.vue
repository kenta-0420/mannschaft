<script setup lang="ts">
/**
 * F02.6 チームお知らせ一覧ページ。
 *
 * チームのお知らせを全件表示する。カーソルページング対応。
 * ADMIN の場合: ピン留め・削除・お知らせ追加ボタンを表示。
 *
 * F08.9 P4b: アイテムクリック時にペイウォール判定を行い、
 * ロックされている場合は PaywallLock UI を表示する（モーダル）。
 *
 * 権限: チームメンバー以上（middleware: auth で保護）
 */
import type { GateCheckResponse } from '~/types/payment'
import type { SpotlightItem } from '~/composables/useSpotlightApi'

definePageMeta({ layout: 'team', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)

const { isAdmin, loadPermissions } = useRoleAccess('team', teamSlug)

const {
  feed,
  meta,
  loading,
  error,
  fetchFeed,
  togglePin,
  deleteAnnouncement,
  markAsRead,
  markAllAsRead,
} = useAnnouncementFeed('TEAM', teamSlug)

const { checkAccess } = useContentGateApi()

const confirmDialog = useConfirm()

// ── F09.19.4 IN_FEED 掲載面（お知らせフィード内に 1 ページ 1 枠） ──────────────
// 非 pinned 3 件目の直後に 1 枠だけ差し込む。ANNOUNCEMENT 広告カード（isAdvertisement）の
// 直前直後は避け、該当時は次のアイテム後ろへ繰り下げる（設計 §8.3）。
const spotlightApi = useSpotlightApi()
const spotlightItems = ref<SpotlightItem[]>([])
/** 掲載面の取得中フラグ（ロード中は差込位置に固定スケルトンを確保して CLS を防ぐ）。 */
const spotlightLoading = ref(true)
/** 差し込む IN_FEED 枠のアイテム（存在時のみ描画）。 */
const spotlightInfeedItem = computed(() => spotlightItems.value[0])

async function loadSpotlight() {
  spotlightLoading.value = true
  try {
    // scopeId には数値のチーム ID が必要。フィード項目の scopeId が数値のチーム ID（BE の Long）。
    const numericTeamId = feed.value[0]?.scopeId
    if (numericTeamId == null) {
      spotlightItems.value = []
      return
    }
    const n = Number(numericTeamId)
    if (!Number.isFinite(n)) {
      spotlightItems.value = []
      return
    }
    spotlightItems.value = await spotlightApi.fetchContent('IN_FEED', 1, {
      scopeType: 'TEAM',
      scopeId: n,
    })
  } finally {
    spotlightLoading.value = false
  }
}

/**
 * IN_FEED 枠を差し込むフラット配列インデックス（この位置のアイテムの「直前」に描画）。
 * 差し込まない場合は -1。
 *
 * <p>差込位置はフィード（非 pinned 3 件目 + 広告カード隣接回避）だけで一意に定まり、
 * 掲載面の取得結果には依存しない。これにより取得中も差込位置にスケルトンを予約でき、
 * item 解決後に差し替える（設計 §8.3 の CLS 防止）。</p>
 */
const spotlightInsertIndex = computed<number>(() => {
  const items = feed.value
  const pinnedCount = items.filter((i) => i.isPinned).length
  const nonPinnedCount = items.length - pinnedCount
  // 非 pinned が 3 件未満なら差し込まない
  if (nonPinnedCount < 3) return -1
  // 非 pinned 3 件目（0-based フラットインデックス）の直後を初期候補にする
  let pos = pinnedCount + 2 + 1
  // ANNOUNCEMENT 広告カードの直前直後を避けて繰り下げる
  while (pos <= items.length) {
    const before = items[pos - 1]
    const after = pos < items.length ? items[pos] : null
    const beforeIsAd = before?.isAdvertisement === true
    const afterIsAd = after?.isAdvertisement === true
    if (!beforeIsAd && !afterIsAd) return pos
    pos += 1
  }
  return -1
})

/** CLS 防止用の固定枠スタイル（既定 96px・応答に高さがあれば優先）。 */
const spotlightInfeedStyle = computed(() => {
  const item = spotlightItems.value[0]
  const h = item?.house?.height ?? item?.affiliate?.height
  return { minHeight: `${h && h > 0 ? h : 96}px` }
})

// ── ペイウォールモーダル状態 ──────────────────────────────────
const paywallModalVisible = ref(false)
const paywallGateLoading = ref(false)
const paywallGateResult = ref<GateCheckResponse | null>(null)
const paywallPendingUrl = ref<string | null>(null)

onMounted(async () => {
  await loadPermissions()
  await fetchFeed({ limit: 20 })
  // 掲載面はフィード取得後（scopeId 確定後）に非同期取得する（失敗してもフィードは止めない）。
  void loadSpotlight()
})

/** 次のページを読み込む */
async function loadMore() {
  if (!meta.value?.hasNext || !meta.value?.nextCursor) return
  await fetchFeed({
    cursor: meta.value.nextCursor,
    limit: 20,
  })
}

/** アイテムクリック: ペイウォール判定 → 既読マーク → 元コンテンツへ遷移 */
async function onItemClick(item: (typeof feed.value)[number]) {
  // F08.9 P4b: お知らせコンテンツ（ANNOUNCEMENT）のペイウォール判定
  paywallGateLoading.value = true
  paywallGateResult.value = null
  paywallPendingUrl.value = item.sourceUrl
  try {
    const res = await checkAccess('ANNOUNCEMENT', item.id)
    paywallGateResult.value = res.data
  } catch {
    // ゲートチェック失敗時は fail-safe: accessible=true として遷移を許可する
    paywallGateResult.value = { accessible: true, titleHidden: false, requiredItems: [] }
  } finally {
    paywallGateLoading.value = false
  }

  if (paywallGateResult.value && !paywallGateResult.value.accessible) {
    // ロックされている場合: モーダルでペイウォールUIを表示
    paywallModalVisible.value = true
    return
  }

  // アクセス可能: 既読マーク → 遷移
  if (!item.isRead) {
    await markAsRead(item.id)
  }
  navigateTo(item.sourceUrl)
}

/** 全件既読 */
async function onMarkAllRead() {
  await markAllAsRead()
}

/** ピン留め切り替え */
async function onTogglePin(id: number) {
  await togglePin(id)
}

/** 削除確認ダイアログ表示 */
function onDeleteConfirm(id: number) {
  confirmDialog.require({
    message: t('announcement.remove_from_announcements'),
    header: t('dialog.confirm_title'),
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    accept: async () => {
      await deleteAnnouncement(id)
    },
  })
}
</script>

<template>
  <div class="mx-auto max-w-3xl p-4">
    <!-- ヘッダー -->
    <div class="mb-4 flex items-center gap-3">
      <PageHeader :title="t('announcement.widget_title')">
        <span class="text-sm text-surface-400">{{ t('announcement.all_announcements') }}</span>
      </PageHeader>
    </div>

    <!-- アクションバー -->
    <div class="mb-4 flex items-center justify-between">
      <span v-if="meta" class="text-sm text-surface-500">
        {{ t('announcement.unread_count', { count: meta.unreadCount }) }}
      </span>
      <Button
        v-if="meta && meta.unreadCount > 0"
        :label="t('announcement.mark_all_read')"
        icon="pi pi-check-circle"
        size="small"
        outlined
        @click="onMarkAllRead"
      />
    </div>

    <!-- ローディング -->
    <PageLoading v-if="loading && feed.length === 0" />

    <!-- エラー -->
    <div v-else-if="error" class="py-8 text-center text-sm text-red-500">
      {{ error }}
    </div>

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="feed.length === 0"
      icon="pi pi-bell"
      :message="t('announcement.empty')"
    />

    <!-- 一覧 -->
    <div v-else>
      <SectionCard>
        <div role="list" class="divide-y divide-surface-100 dark:divide-surface-700">
          <template v-for="(item, idx) in feed" :key="item.id">
            <AnnouncementItem
              :item="item"
              :show-pin-control="isAdmin"
              @click="onItemClick"
              @pin="onTogglePin"
              @delete="onDeleteConfirm"
            />
            <!-- IN_FEED 掲載面（非 pinned 3 件目直後・1 ページ 1 枠） -->
            <!-- CLS 防止: 取得中は 96px 固定スケルトンで枠を確保し、解決後に slot へ差し替える。 -->
            <!-- 取得完了かつ候補なし（item 無し）のときのみ枠を畳む。 -->
            <div
              v-if="idx === spotlightInsertIndex - 1 && (spotlightLoading || spotlightInfeedItem)"
              class="spotlight-infeed py-2"
              data-testid="spotlight-infeed"
              :style="spotlightInfeedStyle"
            >
              <div
                v-if="spotlightLoading"
                class="spotlight-infeed-skeleton w-full animate-pulse rounded-xl bg-surface-100 dark:bg-surface-700"
                style="min-height: 96px"
                data-testid="spotlight-infeed-skeleton"
              />
              <SpotlightSlot
                v-else-if="spotlightInfeedItem"
                :item="spotlightInfeedItem"
                placement="IN_FEED"
              />
            </div>
          </template>
        </div>
      </SectionCard>

      <!-- もっと見るボタン -->
      <div v-if="meta?.hasNext" class="mt-4 flex justify-center">
        <Button
          :label="t('button.next')"
          icon="pi pi-chevron-down"
          outlined
          :loading="loading"
          @click="loadMore"
        />
      </div>
    </div>

    <!-- F08.9 P4b: ペイウォールモーダル -->
    <Dialog
      v-model:visible="paywallModalVisible"
      :header="$t('payment.paywall.locked')"
      :modal="true"
      :closable="true"
      :draggable="false"
      class="w-full max-w-md"
    >
      <PaywallLock :loading="paywallGateLoading" :gate-result="paywallGateResult" />
    </Dialog>
  </div>
</template>
