<script setup lang="ts">
// F09.19.6 チームスコープ 定期レポート 一覧。
// 組織版 (pages/organizations/[slug]/advertiser/report-schedules.vue) を金型に、
// team scope で実装済みの一覧 API（GET /api/v1/teams/{teamId}/advertiser/report-schedules。
// F09.19.5 AC-5.2 / TeamAdvertiserDashboardController）のみを利用する。
//
// 新規作成・削除は、team scope 向けの POST/DELETE
// /api/v1/teams/{teamId}/advertiser/report-schedules(/{id}) が
// バックエンド未実装（AdReportScheduleService.create/delete が ScopeType.ORGANIZATION 固定のまま）
// のため、本ページでは提供しない。

import type { ReportScheduleResponse } from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
const advertiserApi = useAdvertiserApi()

const schedules = ref<ReportScheduleResponse[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await advertiserApi.getReportSchedules('TEAM', teamSlug)
    schedules.value = res.data
  }
  catch { schedules.value = [] }
  finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader :title="t('advertising.teams_page.report_schedules.title')" class="mb-4" />

    <Message severity="info" :closable="false" class="mb-4">
      {{ t('advertising.teams_page.report_schedules.write_ops_notice') }}
    </Message>

    <div v-if="loading" class="flex justify-center py-10"><LoadingBounce /></div>

    <div v-else-if="schedules.length === 0" class="py-10 text-center text-surface-500">
      定期レポートはまだ設定されていません。
    </div>

    <div v-else class="space-y-3">
      <div v-for="s in schedules" :key="s.id" class="flex items-center justify-between rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
        <div>
          <Tag :value="s.frequency" :severity="s.frequency === 'WEEKLY' ? 'info' : 'warn'" class="mr-2" />
          <span class="text-sm">{{ s.recipients.join(', ') }}</span>
          <p v-if="s.lastSentAt" class="mt-1 text-xs text-surface-400">最終配信: {{ s.lastSentAt }}</p>
        </div>
      </div>
    </div>
  </div>
</template>
