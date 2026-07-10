<script setup lang="ts">
// F09.19.6 チームスコープ 与信枠増額申請 一覧。
// 組織版 (pages/organizations/[slug]/advertiser/credit-limit-requests.vue) を金型に、
// team scope で実装済みの一覧 API（GET /api/v1/teams/{teamId}/advertiser/credit-limit-requests。
// F09.19.5 AC-5.2 / TeamAdvertiserDashboardController）のみを利用する。
//
// 新規申請は、team scope 向けの POST /api/v1/teams/{teamId}/advertiser/credit-limit-requests が
// バックエンド未実装（AdCreditLimitRequestService.create が ScopeType.ORGANIZATION 固定のまま）
// のため、本ページでは提供しない。

import type { CreditLimitRequestResponse } from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
const advertiserApi = useAdvertiserApi()

const requests = ref<CreditLimitRequestResponse[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const res = await advertiserApi.getCreditLimitRequests('TEAM', teamSlug)
    requests.value = res.data
  }
  catch { requests.value = [] }
  finally { loading.value = false }
}

function statusSeverity(status: string) {
  return status === 'APPROVED' ? 'success' : status === 'REJECTED' ? 'danger' : 'warn'
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader :title="t('advertising.teams_page.credit_limit_requests.title')" class="mb-4" />

    <Message severity="info" :closable="false" class="mb-4">
      {{ t('advertising.teams_page.credit_limit_requests.write_ops_notice') }}
    </Message>

    <div v-if="loading" class="flex justify-center py-10"><LoadingBounce /></div>

    <DataTable v-else :value="requests" striped-rows>
      <Column field="requestedLimit" header="希望額">
        <template #body="{ data }">¥{{ data.requestedLimit.toLocaleString() }}</template>
      </Column>
      <Column field="currentLimit" header="申請時の限度額">
        <template #body="{ data }">¥{{ data.currentLimit.toLocaleString() }}</template>
      </Column>
      <Column field="reason" header="理由" />
      <Column field="status" header="ステータス">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column field="reviewNote" header="審査メモ" />
      <Column field="createdAt" header="申請日">
        <template #body="{ data }">{{ data.createdAt?.substring(0, 10) }}</template>
      </Column>
    </DataTable>
  </div>
</template>
