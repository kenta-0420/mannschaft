<script setup lang="ts">
import type { AdvertiserAccountResponse, AdvertiserOverviewResponse } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const advertiserApi = useAdvertiserApi()
const account = ref<AdvertiserAccountResponse | null>(null)
const overview = ref<AdvertiserOverviewResponse | null>(null)
const loading = ref(true)
const notRegistered = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await advertiserApi.getAccount(orgId)
    account.value = res.data
    const ovRes = await advertiserApi.getOverview(orgId)
    overview.value = ovRes.data
  }
  catch (e: unknown) {
    if ((e as { response?: { status?: number } })?.response?.status === 404) {
      notRegistered.value = true
    }
  }
  finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <ProgressSpinner v-if="loading" class="flex justify-center py-20" />

    <!-- 未登録 -->
    <div v-else-if="notRegistered" class="mx-auto max-w-lg py-20 text-center">
      <i class="pi pi-megaphone mb-4 text-6xl text-surface-400" />
      <h2 class="mb-2 text-2xl font-bold">広告主ダッシュボード</h2>
      <p class="mb-6 text-surface-500">広告を出稿するには、まず広告主として登録してください。</p>
      <NuxtLink :to="`/organizations/${orgId}/advertiser/register`">
        <Button label="広告主登録" icon="pi pi-user-plus" />
      </NuxtLink>
      <div class="mt-4">
        <NuxtLink :to="`/organizations/${orgId}/advertiser/rate-simulator`">
          <Button label="料金シミュレーター" icon="pi pi-calculator" severity="secondary" text />
        </NuxtLink>
      </div>
    </div>

    <!-- 登録済みダッシュボード -->
    <div v-else-if="account">
      <div class="mb-6 flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold">広告主ダッシュボード</h1>
          <p class="text-surface-500">{{ account.companyName }}</p>
        </div>
        <Tag :value="account.status" :severity="account.status === 'ACTIVE' ? 'success' : account.status === 'PENDING' ? 'warn' : 'danger'" />
      </div>

      <!-- PENDING アラート -->
      <Message v-if="account.status === 'PENDING'" severity="warn" class="mb-4" :closable="false">
        アカウントは審査中です。承認後に広告の配信を開始できます。
      </Message>
      <Message v-if="account.status === 'SUSPENDED'" severity="error" class="mb-4" :closable="false">
        アカウントが停止されています。管理者にお問い合わせください。
      </Message>

      <!-- KPI カード -->
      <div v-if="overview" class="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
          <p class="text-sm text-surface-500">キャンペーン</p>
          <p class="text-2xl font-bold">{{ overview.activeCampaigns }} <span class="text-sm font-normal text-surface-400">/ {{ overview.totalCampaigns }}</span></p>
        </div>
        <div class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
          <p class="text-sm text-surface-500">インプレッション</p>
          <p class="text-2xl font-bold">{{ overview.totalImpressions.toLocaleString() }}</p>
        </div>
        <div class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
          <p class="text-sm text-surface-500">クリック / CTR</p>
          <p class="text-2xl font-bold">{{ overview.totalClicks.toLocaleString() }} <span class="text-sm font-normal text-surface-400">{{ overview.avgCtr }}%</span></p>
        </div>
        <div class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
          <p class="text-sm text-surface-500">広告費（税抜）</p>
          <p class="text-2xl font-bold">¥{{ overview.totalCost.toLocaleString() }}</p>
          <ProgressBar :value="overview.monthlyBudgetUsedPct" class="mt-2" style="height: 6px" />
          <p class="mt-1 text-xs text-surface-400">予算消化 {{ overview.monthlyBudgetUsedPct }}%</p>
        </div>
      </div>

      <!-- キャンペーン一覧 -->
      <div v-if="overview && overview.campaigns.length > 0" class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800">
        <h3 class="mb-3 font-semibold">キャンペーン</h3>
        <DataTable :value="overview.campaigns" :rows="10" striped-rows>
          <Column field="campaignName" header="キャンペーン名" />
          <Column field="status" header="ステータス">
            <template #body="{ data }">
              <Tag :value="data.status" :severity="data.status === 'ACTIVE' ? 'success' : 'secondary'" />
            </template>
          </Column>
          <Column field="impressions" header="imp" class="text-right">
            <template #body="{ data }">{{ data.impressions.toLocaleString() }}</template>
          </Column>
          <Column field="clicks" header="click" class="text-right">
            <template #body="{ data }">{{ data.clicks.toLocaleString() }}</template>
          </Column>
          <Column field="ctr" header="CTR">
            <template #body="{ data }">{{ data.ctr }}%</template>
          </Column>
          <Column field="cost" header="費用" class="text-right">
            <template #body="{ data }">¥{{ data.cost.toLocaleString() }}</template>
          </Column>
          <Column header="">
            <template #body="{ data }">
              <NuxtLink :to="`/organizations/${orgId}/advertiser/campaigns/${data.campaignId}`">
                <Button icon="pi pi-chart-bar" text size="small" />
              </NuxtLink>
            </template>
          </Column>
        </DataTable>
      </div>

      <!-- ナビゲーション -->
      <div class="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <NuxtLink :to="`/organizations/${orgId}/advertiser/invoices`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
            <i class="pi pi-file-edit mb-2 text-2xl text-primary" />
            <p class="text-sm">請求書</p>
          </div>
        </NuxtLink>
        <NuxtLink :to="`/organizations/${orgId}/advertiser/rate-simulator`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
            <i class="pi pi-calculator mb-2 text-2xl text-primary" />
            <p class="text-sm">料金シミュレーター</p>
          </div>
        </NuxtLink>
        <NuxtLink :to="`/organizations/${orgId}/advertiser/report-schedules`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
            <i class="pi pi-calendar-clock mb-2 text-2xl text-primary" />
            <p class="text-sm">定期レポート</p>
          </div>
        </NuxtLink>
        <NuxtLink :to="`/organizations/${orgId}/advertiser/credit-limit-requests`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
            <i class="pi pi-wallet mb-2 text-2xl text-primary" />
            <p class="text-sm">与信枠申請</p>
          </div>
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
