<script setup lang="ts">
/**
 * F09.19.4 SpotlightSlot — 掲載面の末端表示コンポーネント（設計 §8.1）。
 *
 * <p>表示専用。データ取得は行わず props で受け取る（2 枠が同一候補になる事故を
 * 親側の count=2 一括取得で構造的に防ぐため）。</p>
 *
 * <p>中立命名（設計 §4）: レンダリングされる DOM の class / id / data-testid は
 * 全て {@code spotlight-*} に統一し、{@code ad-} / {@code banner-} / {@code sponsor-} /
 * {@code promo-} を含む識別子を一切出力しない（広告ブロッカー耐性・AC-4.10）。</p>
 *
 * <ul>
 *   <li>HOUSE: 画像バナー（imageUrl NULL 時はタイトルカード）＋景表法「広告」ラベル
 *       ＋広告主名＋右上ケバブ。可視計測（50%×1秒で view）とクリック計測（visit）を行う。</li>
 *   <li>AFFILIATE: provider 別の汎用カード（i18n）。計測なし・ケバブなし。
 *       「PR」バッジ（景表法）は維持、星評価は廃止（設計 §8.1）。</li>
 * </ul>
 */
import type { AdPlacement, SpotlightItem } from '~/composables/useSpotlightApi'

const props = defineProps<{
  item: SpotlightItem
  placement: AdPlacement
}>()

const { t } = useI18n()
const spotlightApi = useSpotlightApi()
const adPrefsApi = useAdPreferencesApi()
const notification = useNotification()

const house = computed(() => props.item.house ?? null)
const affiliate = computed(() => props.item.affiliate ?? null)
const isHouse = computed(() => props.item.source === 'HOUSE' && house.value != null)
const isAffiliate = computed(() => props.item.source === 'AFFILIATE' && affiliate.value != null)

/** ケバブ「この広告主を非表示」実行後、自枠を即時非表示にするフラグ。 */
const hidden = ref(false)

/** 景表法ラベル文言（既存 i18n を再利用）。 */
const adLabel = computed(() => t('advertising.ad_label'))

/** アフィリエイト provider（小文字）: i18n キーの組み立てに使う。 */
const affiliateProvider = computed(() => (affiliate.value?.provider ?? '').toLowerCase())

const affiliateTitle = computed(() =>
  t(`advertising.spotlight.affiliate_default.${affiliateProvider.value}.title`),
)
const affiliateDescription = computed(() =>
  t(`advertising.spotlight.affiliate_default.${affiliateProvider.value}.description`),
)
const affiliateCta = computed(() =>
  t(`advertising.spotlight.affiliate_default.${affiliateProvider.value}.cta`),
)

// ── 計測（HOUSE のみ） ─────────────────────────────────────────
const slotRef = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null
let visibleTimer: ReturnType<typeof setTimeout> | null = null
const viewRecorded = ref(false)
/** view で採番した impressionId（visit に紐付ける）。 */
const viewImpressionId = ref<number | null>(null)

function clearVisibleTimer() {
  if (visibleTimer) {
    clearTimeout(visibleTimer)
    visibleTimer = null
  }
}

async function fireView() {
  const h = house.value
  if (!h?.creativeId || viewRecorded.value) return
  viewRecorded.value = true
  const res = await spotlightApi.recordView(h.creativeId, {
    placement: props.placement,
    campaignId: h.campaignId,
    messagingCampaignId: h.messagingCampaignId,
    deliveryId: h.deliveryId,
  })
  if (res?.impressionId != null) {
    viewImpressionId.value = res.impressionId
  }
  // 計上完了後は監視不要
  if (observer) {
    observer.disconnect()
    observer = null
  }
}

function setupViewTracking() {
  if (!isHouse.value || !slotRef.value) return
  if (typeof window === 'undefined' || !('IntersectionObserver' in window)) return
  observer = new IntersectionObserver(
    (entries) => {
      const entry = entries[0]
      if (!entry) return
      // 「50% 以上が 1 秒以上連続可視」で 1 回だけ計上
      if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
        if (!visibleTimer && !viewRecorded.value) {
          visibleTimer = setTimeout(() => {
            visibleTimer = null
            void fireView()
          }, 1000)
        }
      } else {
        clearVisibleTimer()
      }
    },
    { threshold: [0, 0.5, 1] },
  )
  observer.observe(slotRef.value)
}

onMounted(() => {
  setupViewTracking()
})

onBeforeUnmount(() => {
  clearVisibleTimer()
  if (observer) {
    observer.disconnect()
    observer = null
  }
})

/**
 * HOUSE クリック: visit を fire-and-forget で送り、ネイティブ遷移（新規タブ）は妨げない。
 * preventDefault しないため `<a target="_blank">` が destinationUrl を新規タブで開く。
 */
function onHouseClick() {
  const h = house.value
  if (!h?.creativeId) return
  spotlightApi.recordVisit(h.creativeId, {
    placement: props.placement,
    impressionId: viewImpressionId.value ?? undefined,
    campaignId: h.campaignId,
    messagingCampaignId: h.messagingCampaignId,
    deliveryId: h.deliveryId,
  })
}

// ── ケバブメニュー（HOUSE のみ） ──────────────────────────────
const menuOpen = ref(false)

function toggleMenu() {
  menuOpen.value = !menuOpen.value
}

function closeMenu() {
  menuOpen.value = false
}

// ── 通報（HOUSE のみ・F09.19.9） ──────────────────────────────
const reportModalVisible = ref(false)

/** メッセージ型予約バナーは messagingCampaignId(UUID)、運用型は operationalCampaignId(=campaignId) を送る。 */
const reportCampaignId = computed<string | null>(() => house.value?.messagingCampaignId ?? null)
const reportOperationalCampaignId = computed<number | null>(() =>
  house.value?.messagingCampaignId ? null : (house.value?.campaignId ?? null),
)

function openReport() {
  closeMenu()
  reportModalVisible.value = true
}

/**
 * 「この広告主を非表示」: 受信設定の blockedAdvertiserAccountIds に追加し、自枠を即時非表示にする。
 * 現在のブロック一覧を取得してから追記する（部分更新 API は現値を維持するため）。
 */
async function hideAdvertiser() {
  closeMenu()
  const advertiserId = house.value?.advertiserAccountId
  if (advertiserId == null) return
  try {
    const current = await adPrefsApi.getPreferences()
    const blocked = [...(current.data.blockedAdvertiserAccountIds ?? [])]
    if (!blocked.includes(advertiserId)) {
      blocked.push(advertiserId)
    }
    await adPrefsApi.updatePreferences({ blockedAdvertiserAccountIds: blocked })
    notification.success(t('advertising.spotlight.hide_advertiser_done'))
    hidden.value = true
  } catch {
    // 永続化に失敗した場合は枠を隠さない（誤って隠すと不整合になる）
    notification.error(t('error.server_retry'))
  }
}
</script>

<template>
  <div
    v-if="!hidden"
    ref="slotRef"
    class="spotlight-slot relative flex flex-col overflow-hidden rounded-xl border-2 border-surface-400 bg-surface-0 shadow-sm transition-all hover:shadow-md dark:border-surface-500 dark:bg-surface-800"
    data-testid="spotlight-slot"
  >
    <!-- ══════════ HOUSE ══════════ -->
    <template v-if="isHouse && house">
      <a
        :href="house.destinationUrl ?? '#'"
        target="_blank"
        rel="noopener sponsored"
        class="spotlight-house group flex flex-col p-3"
        data-testid="spotlight-house"
        @click="onHouseClick"
      >
        <!-- 景表法「広告」ラベル -->
        <span
          class="spotlight-label absolute left-2.5 top-2.5 z-10 rounded px-1.5 py-0.5 text-[10px] font-bold leading-none text-white"
          data-testid="spotlight-label"
          role="region"
          :aria-label="adLabel"
        >
          {{ adLabel }}
        </span>

        <!-- 画像バナー（imageUrl NULL 時はタイトルカード） -->
        <img
          v-if="house.imageUrl"
          :src="house.imageUrl"
          :alt="house.altText || house.title || adLabel"
          class="spotlight-image mb-2 w-full rounded-lg object-cover"
        >
        <div
          v-else
          class="spotlight-textcard mb-2 flex min-h-[80px] items-center justify-center rounded-lg bg-surface-100 p-3 text-center dark:bg-surface-700"
        >
          <span class="spotlight-title text-sm font-semibold text-surface-700 dark:text-surface-200">
            {{ house.title }}
          </span>
        </div>

        <!-- 広告主名 -->
        <span class="spotlight-advertiser truncate text-[11px] text-surface-500">
          {{ house.advertiserName }}
        </span>
      </a>

      <!-- ケバブメニュー（HOUSE のみ・第1弾は「この広告主を非表示」のみ／「通報」は F09.19.9 まで出さない） -->
      <div class="spotlight-menu absolute right-1.5 top-1.5 z-20">
        <button
          type="button"
          class="spotlight-menu-toggle flex h-6 w-6 items-center justify-center rounded-full bg-surface-0/80 text-surface-500 backdrop-blur-sm hover:bg-surface-100 dark:bg-surface-800/80 dark:hover:bg-surface-700"
          data-testid="spotlight-menu-button"
          :aria-label="t('advertising.spotlight.hide_advertiser')"
          @click.stop.prevent="toggleMenu"
        >
          <i class="pi pi-ellipsis-v text-xs" />
        </button>
        <ul
          v-if="menuOpen"
          class="spotlight-menu-list absolute right-0 top-7 min-w-[10rem] rounded-lg border border-surface-200 bg-surface-0 py-1 shadow-lg dark:border-surface-600 dark:bg-surface-800"
          data-testid="spotlight-menu-list"
        >
          <li>
            <button
              type="button"
              class="spotlight-menu-item block w-full px-3 py-1.5 text-left text-xs text-surface-700 hover:bg-surface-100 dark:text-surface-200 dark:hover:bg-surface-700"
              data-testid="spotlight-hide-advertiser"
              @click.stop.prevent="hideAdvertiser"
            >
              {{ t('advertising.spotlight.hide_advertiser') }}
            </button>
          </li>
          <li>
            <button
              type="button"
              class="spotlight-menu-item block w-full px-3 py-1.5 text-left text-xs text-surface-700 hover:bg-surface-100 dark:text-surface-200 dark:hover:bg-surface-700"
              data-testid="spotlight-report"
              @click.stop.prevent="openReport"
            >
              {{ t('advertising.spotlight.report') }}
            </button>
          </li>
        </ul>
      </div>

      <!-- 通報モーダル（F09.19.9） -->
      <AdReportModal
        v-model:visible="reportModalVisible"
        :campaign-id="reportCampaignId"
        :operational-campaign-id="reportOperationalCampaignId"
      />
    </template>

    <!-- ══════════ AFFILIATE ══════════ -->
    <a
      v-else-if="isAffiliate && affiliate"
      :href="affiliate.affiliateUrl ?? '#'"
      target="_blank"
      rel="noopener sponsored"
      class="spotlight-affiliate group flex flex-col p-3"
      data-testid="spotlight-affiliate"
    >
      <!-- PR バッジ（景表法ラベル・維持） -->
      <span
        class="spotlight-pr absolute right-2.5 top-2.5 rounded-full bg-surface-100 px-2 py-0.5 text-[10px] font-medium text-surface-400 dark:bg-surface-700"
        data-testid="spotlight-pr"
      >
        PR
      </span>

      <!-- バナー画像がある場合は優先表示 -->
      <img
        v-if="affiliate.bannerImageUrl"
        :src="affiliate.bannerImageUrl"
        :alt="affiliate.altText || affiliateTitle"
        class="spotlight-image mb-2 w-full rounded-lg object-cover"
      >

      <template v-else>
        <!-- ヘッダー（provider アイコン + タイトル） -->
        <div class="mb-2 flex items-center gap-2.5">
          <div
            v-if="affiliateProvider === 'amazon'"
            class="spotlight-provider-icon flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#FF9900]/10 transition-colors group-hover:bg-[#FF9900]/20"
          >
            <svg viewBox="0 0 24 24" class="h-5 w-5 fill-[#FF9900]" xmlns="http://www.w3.org/2000/svg">
              <path
                d="M13.958 10.09c0 1.232.029 2.256-.591 3.351-.502.891-1.301 1.439-2.186 1.439-1.214 0-1.922-.924-1.922-2.292 0-2.692 2.415-3.182 4.699-3.182v.684zm3.186 7.705c-.209.189-.512.201-.745.074-1.047-.872-1.234-1.276-1.813-2.106-1.733 1.769-2.96 2.298-5.208 2.298-2.658 0-4.729-1.641-4.729-4.925 0-2.565 1.391-4.309 3.37-5.164 1.715-.754 4.11-.891 5.942-1.099v-.41c0-.753.06-1.642-.383-2.294-.384-.578-1.124-.816-1.775-.816-1.205 0-2.277.618-2.54 1.897-.054.285-.261.567-.547.582l-3.065-.333c-.259-.056-.545-.266-.47-.661C5.945 2.042 8.918 1 11.588 1c1.42 0 3.275.378 4.394 1.458C17.371 3.627 17.25 5.209 17.25 6.934v4.867c0 1.466.607 2.106 1.178 2.894.2.282.244.619-.01.828-.638.532-1.77 1.521-2.39 2.073l-.884-.801z"
              />
              <path
                d="M21.525 18.635c-.248-.319-1.637-.151-2.261-.076-.19.023-.219-.143-.048-.263 1.107-.778 2.923-.554 3.135-.293.212.262-.055 2.083-1.096 2.952-.16.134-.312.063-.241-.114.234-.584.759-1.887.511-2.206z"
              />
            </svg>
          </div>
          <div
            v-else-if="affiliateProvider === 'rakuten'"
            class="spotlight-provider-icon flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#BF0000]/10 transition-colors group-hover:bg-[#BF0000]/20"
          >
            <svg viewBox="0 0 24 24" class="h-5 w-5 fill-[#BF0000]" xmlns="http://www.w3.org/2000/svg">
              <path
                d="M13.388 11.26h-2.137V8.397h2.137c.853 0 1.46.56 1.46 1.432 0 .871-.607 1.431-1.46 1.431zm4.418-.095c0-2.512-1.802-4.1-4.612-4.1H8.918V17h2.333v-3.607h1.46L14.638 17h2.658l-2.27-3.9c1.607-.507 2.78-1.7 2.78-3.935zM12 2C6.477 2 2 6.477 2 12s4.477 10 10 10 10-4.477 10-10S17.523 2 12 2z"
              />
            </svg>
          </div>
          <div class="min-w-0 flex-1">
            <h3 class="spotlight-title text-sm font-semibold text-surface-700 dark:text-surface-200">
              {{ affiliateTitle }}
            </h3>
          </div>
        </div>

        <!-- 説明文 -->
        <p class="spotlight-desc mb-2 text-xs text-surface-600 dark:text-surface-300">
          {{ affiliateDescription }}
        </p>

        <!-- CTA ボタン -->
        <div class="mt-auto">
          <span
            class="spotlight-cta inline-flex w-full items-center justify-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold text-white shadow-sm transition-all"
            :class="
              affiliateProvider === 'rakuten'
                ? 'bg-[#BF0000] group-hover:bg-[#a00000]'
                : 'bg-[#FF9900] group-hover:bg-[#e68900]'
            "
          >
            {{ affiliateCta }}
            <i class="pi pi-external-link text-[10px]" />
          </span>
        </div>
      </template>
    </a>

    <!-- アップグレード CTA（広告なしで使う） -->
    <NuxtLink
      to="/me/payments/subscriptions"
      class="spotlight-upgrade block border-t border-surface-100 px-3 py-1.5 text-center text-[11px] text-surface-400 hover:text-primary dark:border-surface-700"
      data-testid="spotlight-upgrade"
    >
      {{ t('advertising.spotlight.upgrade_cta') }}
    </NuxtLink>
  </div>
</template>

<style scoped>
/* 景表法「広告」ラベルは AdLabelBadge と同一視覚仕様（橙 #FF9800 背景・白字） */
.spotlight-label {
  background-color: #ff9800;
}
</style>
