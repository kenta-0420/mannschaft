<script setup lang="ts">
// F09.19.6 チーム広告主トップページ。
//
// 組織版 (pages/organizations/[slug]/advertiser/index.vue) と異なり、team scope 向けの
// アカウント概況取得 API（GET /api/v1/advertiser/account・/overview）はバックエンドで
// ScopeType.ORGANIZATION 固定のまま（F09.19.5 でも scope 化されず）のため、
// KPI ダッシュボード表示は行わずナビゲーションのみ提供する。
//
// invoices / credit-limit-requests / report-schedules は一覧表示のみ team 対応済み
// （F09.19.5 AC-5.2）。新規作成・詳細・PDF 等の書き込み系操作は各ページ内で
// 「組織版でのみご利用いただけます」と案内する（F09.19.6 時点でのバックエンドギャップ。
// 正本 docs/features/F09.19_ad_slot_serving.md §16 F09.19.6 参照）。
// rate-simulator は scope 非依存 API のため org 版と同一に動作する。
// 広告主登録（register.vue）は F09.17 Phase 11-d-4 で実装済みのためタイルから遷移できる。

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
</script>

<template>
  <div>
    <PageHeader :title="t('advertising.teams_page.title')" />
    <p class="mt-1 mb-6 text-sm text-surface-500">
      {{ t('advertising.teams_page.description') }}
    </p>

    <!-- ナビゲーション -->
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <NuxtLink :to="`/teams/${teamSlug}/advertiser/register`">
        <div class="cursor-pointer rounded-lg border border-surface-300 p-4 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
          <i class="pi pi-user-plus mb-2 text-2xl text-primary" />
          <p class="text-sm font-medium">{{ t('advertising.teams_page.nav.register') }}</p>
        </div>
      </NuxtLink>
      <NuxtLink :to="`/teams/${teamSlug}/advertiser/messaging-campaigns`">
        <div class="cursor-pointer rounded-lg border border-surface-300 p-4 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
          <i class="pi pi-send mb-2 text-2xl text-primary" />
          <p class="text-sm font-medium">{{ t('advertising.advertiser_crud.nav.messaging_campaigns') }}</p>
          <p class="mt-1 text-xs text-surface-500">
            {{ t('advertising.teams_page.nav.messaging_campaigns_description') }}
          </p>
        </div>
      </NuxtLink>
      <NuxtLink :to="`/teams/${teamSlug}/advertiser/invoices`">
        <div class="cursor-pointer rounded-lg border border-surface-300 p-4 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
          <i class="pi pi-file-edit mb-2 text-2xl text-primary" />
          <p class="text-sm font-medium">{{ t('advertising.teams_page.nav.invoices') }}</p>
        </div>
      </NuxtLink>
      <NuxtLink :to="`/teams/${teamSlug}/advertiser/report-schedules`">
        <div class="cursor-pointer rounded-lg border border-surface-300 p-4 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
          <i class="pi pi-calendar-clock mb-2 text-2xl text-primary" />
          <p class="text-sm font-medium">{{ t('advertising.teams_page.nav.report_schedules') }}</p>
        </div>
      </NuxtLink>
      <NuxtLink :to="`/teams/${teamSlug}/advertiser/credit-limit-requests`">
        <div class="cursor-pointer rounded-lg border border-surface-300 p-4 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
          <i class="pi pi-wallet mb-2 text-2xl text-primary" />
          <p class="text-sm font-medium">{{ t('advertising.teams_page.nav.credit_limit_requests') }}</p>
        </div>
      </NuxtLink>
      <NuxtLink :to="`/teams/${teamSlug}/advertiser/rate-simulator`">
        <div class="cursor-pointer rounded-lg border border-surface-300 p-4 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
          <i class="pi pi-calculator mb-2 text-2xl text-primary" />
          <p class="text-sm font-medium">{{ t('advertising.teams_page.nav.rate_simulator') }}</p>
        </div>
      </NuxtLink>
    </div>
  </div>
</template>
