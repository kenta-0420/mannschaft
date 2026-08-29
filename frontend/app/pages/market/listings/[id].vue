<script setup lang="ts">
/**
 * F22.1 市（Market）— 公開札詳細ページ
 *
 * - 未ログイン公開（middleware なし・permitAll）
 * - 公開札（visibility='PUBLIC'）のみ表示。非公開/scope限定は 404。
 * - 未ログイン: 応募ボタンを「ログインして応募」に置換。
 * - ログイン済み: 「札に応じる」ボタン（応募API呼び出し）。
 * - PII抑制: 主催はチーム公称名+アイコンのみ表示。
 *
 * 設計書: docs/features/F22.1_market/03_ui_i18n.md §3
 * API:    GET /api/v1/public/market/listings/{id}
 *         POST /api/v1/recruitment-listings/{id}/applications
 */
import type { MarketListingRegion, MarketListingResponse } from '~/types/market'
import type { ScopeKind } from '~/types/marketPayment'
import type { RecruitmentParticipantResponse } from '~/types/recruitment'

definePageMeta({
  layout: 'default',
})

const { t, locale } = useI18n()
const route = useRoute()
const marketApi = useMarketApi()
const recruitmentApi = useRecruitmentApi()
const authStore = useAuthStore()
const teamStore = useTeamStore()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const listingId = computed(() => {
  const raw = route.params.id
  const idStr = Array.isArray(raw) ? raw[0] : raw
  const n = Number(idStr)
  if (!Number.isFinite(n) || n <= 0) {
    throw createError({ statusCode: 404, statusMessage: 'Listing not found' })
  }
  return n
})

const listing = ref<MarketListingResponse | null>(null)
const pageLoading = ref(true)
const applying = ref(false)
const participationLoading = ref(authStore.isAuthenticated)
const participationLoadFailed = ref(false)
const myParticipation = ref<RecruitmentParticipantResponse | null>(null)
const autoOpenPayment = ref(false)
/** TEAM 型募集のときに選択されたチームID。 */
const selectedTeamId = ref<number | null>(null)

async function load() {
  pageLoading.value = true
  try {
    const res = await marketApi.getMarketListing(listingId.value, locale.value)
    listing.value = res.data
  }
  catch (err) {
    const status =
      typeof err === 'object' && err !== null && 'response' in err
        ? (err as { response?: { status?: number } }).response?.status
        : undefined
    if (status === 404) {
      throw createError({ statusCode: 404, statusMessage: t('market.detail.notFound') })
    }
    throw err
  }
  finally {
    pageLoading.value = false
  }
}

await load()

// TEAM 型の募集であれば所属チーム一覧を先読み。
if (authStore.isAuthenticated && listing.value?.participationType === 'TEAM') {
  await teamStore.fetchMyTeams()
}

// ロケール切替時に地域名表示を現在ロケールへ追従させる。
watch(locale, async () => {
  await load()
})

const isAuthenticated = computed(() => authStore.isAuthenticated)
const isTeamListing = computed(() => listing.value?.participationType === 'TEAM')
const canApply = computed(() => {
  if (
    participationLoading.value
    || participationLoadFailed.value
    || !isAuthenticated.value
    || listing.value?.status !== 'OPEN'
  ) {
    return false
  }
  if (myParticipation.value != null) return false
  // TEAM 型はチームを選択するまで応募不可。
  if (isTeamListing.value) return selectedTeamId.value !== null
  return true
})

async function loadMyParticipation() {
  if (!authStore.isAuthenticated) {
    myParticipation.value = null
    return
  }
  participationLoading.value = true
  participationLoadFailed.value = false
  try {
    const myList = await recruitmentApi.listMyActiveParticipations()
    myParticipation.value = myList.data.find((p) => p.listingId === listingId.value) ?? null
  }
  catch (err) {
    participationLoadFailed.value = true
    handleApiError(err, t('market.detail.loadFailed'))
  }
  finally {
    participationLoading.value = false
  }
}

onMounted(() => {
  void loadMyParticipation()
})

async function applyToListing() {
  if (!listing.value) return
  applying.value = true
  try {
    const isTeam = listing.value.participationType === 'TEAM'
    const result = await recruitmentApi.applyToListing(listing.value.id, {
      participantType: isTeam ? 'TEAM' : 'USER',
      teamId: isTeam ? selectedTeamId.value : undefined,
    })
    myParticipation.value = result.data
    autoOpenPayment.value = listing.value.paymentEnabled && result.data.status === 'CONFIRMED'
    notification.success(t('recruitment.participantStatus.applied'))
    // 応募後に件数を更新するために再取得
    await load()
  }
  catch (err) {
    handleApiError(err, t('market.action.apply'))
  }
  finally {
    applying.value = false
  }
}

const { formatDateTime: formatDate } = useDatetime()

function statusSeverity(status: string): 'success' | 'warn' | 'secondary' | 'danger' {
  switch (status) {
    case 'OPEN': return 'success'
    case 'FULL': return 'warn'
    case 'COMPLETED': return 'secondary'
    default: return 'danger'
  }
}

/**
 * 複数地域募集（F22.1 Phase2 D）の表示用地域配列を返す。
 * regions[] を優先し、空なら後方互換の単一 region をフォールバックに使う。
 */
function regionTags(l: MarketListingResponse): MarketListingRegion[] {
  if (l.regions && l.regions.length > 0) {
    return l.regions
  }
  return l.region ? [l.region] : []
}

/** 地域 1 件を「都道府県 市区町村」形式に整形する（市区町村が空なら県のみ）。 */
function regionLabel(region: MarketListingRegion): string {
  return region.cityName
    ? `${region.prefectureName} ${region.cityName}`
    : region.prefectureName
}

/**
 * F22.1 謝礼受取（Connect onboarding）導線の対象 scope。
 * 公開札 owner（TEAM/ORGANIZATION）を Connect の ScopeKind（TEAM/ORG）へ写像する。
 * 謝礼あり札（paymentEnabled）かつログイン済みのときのみ表示し、実際の認可（受取側 scope ADMIN）は
 * BE 側で担保する（権限不足は BE が 403/404 を返す）。
 */
const payeeScope = computed<{ kind: ScopeKind, id: number } | null>(() => {
  const l = listing.value
  if (!l || !l.paymentEnabled || !isAuthenticated.value) {
    return null
  }
  const kind: ScopeKind = l.owner.scopeType === 'ORGANIZATION' ? 'ORG' : 'TEAM'
  const id = Number(l.owner.scopeId)
  if (!Number.isFinite(id)) {
    return null
  }
  return { kind, id }
})
</script>

<template>
  <div class="mx-auto max-w-3xl p-6" data-testid="market-detail-page">
    <!-- ローディング -->
    <PageLoading v-if="pageLoading" />

    <template v-else-if="listing">
      <PageHeader :title="listing.title" size="sm" back-to="/market" />

      <SectionCard data-testid="market-detail-card">
        <!-- 主催（PII抑制: 公称名+アイコンのみ） -->
        <div class="mb-4 flex items-center gap-3" data-testid="market-detail-organizer">
          <Avatar
            v-if="listing.owner.iconUrl"
            :image="listing.owner.iconUrl"
            shape="circle"
            size="large"
          />
          <Avatar
            v-else
            :label="listing.owner.displayName.charAt(0) || 'T'"
            shape="circle"
            size="large"
          />
          <div>
            <p class="text-xs text-surface-500">{{ $t('market.detail.organizer') }}</p>
            <p class="font-semibold text-surface-800 dark:text-surface-100" data-testid="market-detail-organizer-name">
              {{ listing.owner.displayName }}
            </p>
          </div>
          <Tag
            :value="$t(`market.status.${listing.status}`)"
            :severity="statusSeverity(listing.status)"
            class="ml-auto"
          />
        </div>

        <!-- カテゴリ -->
        <div class="mb-3 flex items-center gap-2">
          <i class="pi pi-tag text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.category') }}:</span>
          <Tag :value="$t(listing.category.nameKey)" severity="info" />
        </div>

        <!-- 地域（複数地域募集 N:N・F22.1 Phase2 D） -->
        <div class="mb-3 flex items-center gap-2" data-testid="market-detail-regions">
          <i class="pi pi-map-marker text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.region') }}:</span>
          <div class="flex flex-wrap gap-1">
            <Tag
              v-for="region in regionTags(listing)"
              :key="`${region.prefectureCode}-${region.cityCode ?? ''}`"
              :value="regionLabel(region)"
              severity="secondary"
              class="text-xs"
            />
            <span v-if="regionTags(listing).length === 0" class="text-sm">
              {{ $t('market.detail.regionNone') }}
            </span>
          </div>
        </div>

        <!-- 場所（テキスト） -->
        <div v-if="listing.locationText" class="mb-3 flex items-center gap-2">
          <i class="pi pi-building text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.location') }}:</span>
          <span class="text-sm">{{ listing.locationText }}</span>
        </div>

        <!-- 開催日時 -->
        <div class="mb-3 flex items-center gap-2">
          <i class="pi pi-calendar text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.startAt') }}:</span>
          <span class="text-sm">{{ formatDate(listing.startAt) }}</span>
        </div>

        <!-- 応募締切 -->
        <div class="mb-3 flex items-center gap-2">
          <i class="pi pi-clock text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.deadline') }}:</span>
          <span class="text-sm">{{ formatDate(listing.applicationDeadline) }}</span>
        </div>

        <!-- 定員 / 応募数 -->
        <div class="mb-6 flex items-center gap-2">
          <i class="pi pi-users text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.confirmedCount') }}:</span>
          <span class="text-sm font-semibold">
            {{ $t('market.card.capacity', { confirmed: listing.confirmedCount, capacity: listing.capacity }) }}
          </span>
        </div>

        <!-- 応募ボタン -->
        <div class="flex flex-col items-center gap-3" data-testid="market-detail-apply-area">
          <!-- 未ログイン: ログイン誘導 -->
          <Button
            v-if="!isAuthenticated"
            :label="$t('market.action.loginToApply')"
            icon="pi pi-sign-in"
            size="large"
            data-testid="market-login-to-apply-btn"
            @click="navigateTo('/login')"
          />
          <template v-else-if="listing.status === 'OPEN'">
            <!-- TEAM 型: チーム選択ドロップダウン -->
            <Select
              v-if="isTeamListing"
              v-model="selectedTeamId"
              :options="teamStore.myTeams"
              option-label="name"
              option-value="id"
              :placeholder="$t('market.action.selectTeam')"
              class="w-full max-w-xs"
              data-testid="market-team-select"
            />
            <!-- 応募ボタン -->
            <Button
              v-if="myParticipation == null"
              :label="$t('market.action.apply')"
              icon="pi pi-check"
              size="large"
              :loading="applying"
              :disabled="!canApply"
              data-testid="market-apply-btn"
              @click="applyToListing"
            />
            <Tag
              v-else
              :value="$t(`recruitment.participantStatus.${myParticipation.status.toLowerCase()}`)"
              severity="success"
            />
          </template>
          <!-- ログイン済み・OPEN以外 -->
          <Button
            v-else
            :label="$t(`market.status.${listing.status}`)"
            :severity="statusSeverity(listing.status)"
            size="large"
            disabled
          />
        </div>
      </SectionCard>

      <RecruitmentPaymentConfirmationButton
        v-if="listing.paymentEnabled
          && myParticipation?.participantType === 'USER'
          && myParticipation.status === 'CONFIRMED'"
        :listing-id="listing.id"
        :participant-id="myParticipation.id"
        :auto-open="autoOpenPayment"
        class="mt-4"
        @confirmed="autoOpenPayment = false"
      />

      <!-- 謝礼あり札の受取口座（Stripe Connect）登録導線（受取側・F22.1） -->
      <MarketConnectOnboarding
        v-if="payeeScope"
        class="mt-4"
        data-testid="market-connect-onboarding"
        :scope-kind="payeeScope.kind"
        :scope-id="payeeScope.id"
      />
    </template>
  </div>
</template>
