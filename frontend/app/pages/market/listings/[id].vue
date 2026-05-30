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
import type { MarketListingResponse } from '~/types/market'

definePageMeta({
  layout: 'default',
})

const { t } = useI18n()
const route = useRoute()
const marketApi = useMarketApi()
const recruitmentApi = useRecruitmentApi()
const authStore = useAuthStore()
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

async function load() {
  pageLoading.value = true
  try {
    const res = await marketApi.getMarketListing(listingId.value)
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

const isAuthenticated = computed(() => authStore.isAuthenticated)
const canApply = computed(() =>
  isAuthenticated.value
  && listing.value?.status === 'OPEN',
)

async function applyToListing() {
  if (!listing.value) return
  applying.value = true
  try {
    await recruitmentApi.applyToListing(listing.value.id, {
      participantType: 'TEAM',
    })
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

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString()
}

function statusSeverity(status: string): 'success' | 'warn' | 'secondary' | 'danger' {
  switch (status) {
    case 'OPEN': return 'success'
    case 'FULL': return 'warn'
    case 'COMPLETED': return 'secondary'
    default: return 'danger'
  }
}
</script>

<template>
  <div class="mx-auto max-w-3xl p-6">
    <!-- ローディング -->
    <PageLoading v-if="pageLoading" />

    <template v-else-if="listing">
      <!-- 戻るボタン -->
      <div class="mb-4">
        <Button
          icon="pi pi-arrow-left"
          :label="$t('market.title')"
          text
          @click="navigateTo('/market')"
        />
      </div>

      <div class="rounded-xl border border-surface-300 bg-surface-0 p-6 shadow-sm dark:border-surface-600 dark:bg-surface-900">
        <!-- 主催（PII抑制: 公称名+アイコンのみ） -->
        <div class="mb-4 flex items-center gap-3">
          <Avatar
            v-if="listing.owner.icon_url"
            :image="listing.owner.icon_url"
            shape="circle"
            size="large"
          />
          <Avatar
            v-else
            :label="listing.owner.display_name.charAt(0) || 'T'"
            shape="circle"
            size="large"
          />
          <div>
            <p class="text-xs text-surface-500">{{ $t('market.detail.organizer') }}</p>
            <p class="font-semibold text-surface-800 dark:text-surface-100">
              {{ listing.owner.display_name }}
            </p>
          </div>
          <Tag
            :value="$t(`market.status.${listing.status}`)"
            :severity="statusSeverity(listing.status)"
            class="ml-auto"
          />
        </div>

        <!-- タイトル -->
        <h1 class="mb-4 text-2xl font-bold text-surface-900 dark:text-surface-50">
          {{ listing.title }}
        </h1>

        <!-- カテゴリ -->
        <div class="mb-3 flex items-center gap-2">
          <i class="pi pi-tag text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.category') }}:</span>
          <Tag :value="$t(listing.category.name_key)" severity="info" />
        </div>

        <!-- 地域 -->
        <div v-if="listing.region" class="mb-3 flex items-center gap-2">
          <i class="pi pi-map-marker text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.region') }}:</span>
          <span class="text-sm">
            {{ listing.region.prefecture_name }} {{ listing.region.city_name }}
          </span>
        </div>

        <!-- 場所（テキスト） -->
        <div v-if="listing.location_text" class="mb-3 flex items-center gap-2">
          <i class="pi pi-building text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.location') }}:</span>
          <span class="text-sm">{{ listing.location_text }}</span>
        </div>

        <!-- 開催日時 -->
        <div class="mb-3 flex items-center gap-2">
          <i class="pi pi-calendar text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.startAt') }}:</span>
          <span class="text-sm">{{ formatDate(listing.start_at) }}</span>
        </div>

        <!-- 応募締切 -->
        <div class="mb-3 flex items-center gap-2">
          <i class="pi pi-clock text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.deadline') }}:</span>
          <span class="text-sm">{{ formatDate(listing.application_deadline) }}</span>
        </div>

        <!-- 定員 / 応募数 -->
        <div class="mb-6 flex items-center gap-2">
          <i class="pi pi-users text-surface-400" />
          <span class="text-sm text-surface-600">{{ $t('market.detail.confirmedCount') }}:</span>
          <span class="text-sm font-semibold">
            {{ $t('market.card.capacity', { confirmed: listing.confirmed_count, capacity: listing.capacity }) }}
          </span>
        </div>

        <!-- 応募ボタン -->
        <div class="flex justify-center">
          <!-- 未ログイン: ログイン誘導 -->
          <Button
            v-if="!isAuthenticated"
            :label="$t('market.action.loginToApply')"
            icon="pi pi-sign-in"
            size="large"
            @click="navigateTo('/login')"
          />
          <!-- ログイン済み・OPEN: 応募ボタン -->
          <Button
            v-else-if="canApply"
            :label="$t('market.action.apply')"
            icon="pi pi-check"
            size="large"
            :loading="applying"
            @click="applyToListing"
          />
          <!-- ログイン済み・OPEN以外 -->
          <Button
            v-else-if="isAuthenticated"
            :label="$t(`market.status.${listing.status}`)"
            :severity="statusSeverity(listing.status)"
            size="large"
            disabled
          />
        </div>
      </div>
    </template>
  </div>
</template>
