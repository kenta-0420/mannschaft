<script setup lang="ts">
// F09.17 Phase 11-d-4 チーム広告主トップページ。
//
// 組織版 (pages/organizations/[id]/advertiser/index.vue) を簡略化したもの:
//  - メッセージ型キャンペーンへのリンクのみ提供
//  - invoices / credit-limit-requests / report-schedules / rate-simulator は
//    チームスコープでは未対応（将来 Phase 11-e 以降で対応）
//  - 広告主登録 UI も未対応（Stripe 連携独立化が未実装。別タスクで対応予定）

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const teamId = String(route.params.id)
</script>

<template>
  <div>
    <PageHeader :title="t('advertising.teams_page.title')" />
    <p class="mt-1 mb-6 text-sm text-surface-500">
      {{ t('advertising.teams_page.description') }}
    </p>

    <!-- ナビゲーション -->
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <NuxtLink :to="`/teams/${teamId}/advertiser/messaging-campaigns`">
        <div class="cursor-pointer rounded-lg border border-surface-300 p-4 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
          <i class="pi pi-send mb-2 text-2xl text-primary" />
          <p class="text-sm font-medium">{{ t('advertising.advertiser_crud.nav.messaging_campaigns') }}</p>
          <p class="mt-1 text-xs text-surface-500">
            {{ t('advertising.teams_page.nav.messaging_campaigns_description') }}
          </p>
        </div>
      </NuxtLink>
    </div>

    <!-- 将来対応のお知らせ -->
    <Message severity="info" :closable="false" class="mt-6">
      {{ t('advertising.teams_page.future_features_notice') }}
    </Message>
  </div>
</template>
