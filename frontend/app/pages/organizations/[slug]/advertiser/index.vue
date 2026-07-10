<script setup lang="ts">
import type { AdvertiserAccountResponse, AdvertiserOverviewResponse } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const orgSlug = String(route.params.slug)
const advertiserApi = useAdvertiserApi()
const toast = useNotification()
const account = ref<AdvertiserAccountResponse | null>(null)
const overview = ref<AdvertiserOverviewResponse | null>(null)
const loading = ref(true)
const notRegistered = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await advertiserApi.getAccount(orgSlug)
    account.value = res.data
    const ovRes = await advertiserApi.getOverview(orgSlug)
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

// F09.19.7 §10.3: アカウント設定（companyName / contactEmail）編集ダイアログ
const editDialogVisible = ref(false)
const editForm = ref<{ companyName: string; contactEmail: string }>({
  companyName: '',
  contactEmail: '',
})
const savingAccount = ref(false)

function openEditDialog() {
  if (!account.value) return
  editForm.value = {
    companyName: account.value.companyName,
    contactEmail: account.value.contactEmail,
  }
  editDialogVisible.value = true
}

async function saveAccount() {
  savingAccount.value = true
  try {
    const res = await advertiserApi.updateAccount(orgSlug, {
      companyName: editForm.value.companyName,
      contactEmail: editForm.value.contactEmail,
    })
    account.value = res.data
    editDialogVisible.value = false
    toast.success(t('advertising.account_settings.saved'))
  }
  catch {
    toast.error(t('advertising.account_settings.save_failed'))
  }
  finally {
    savingAccount.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div v-if="loading" class="flex justify-center py-20"><LoadingBounce /></div>

    <!-- 未登録 -->
    <div v-else-if="notRegistered" class="mx-auto max-w-lg py-20 text-center">
      <i class="pi pi-megaphone mb-4 text-6xl text-surface-400" />
      <h2 class="mb-2 text-2xl font-bold">広告主ダッシュボード</h2>
      <p class="mb-6 text-surface-500">広告を出稿するには、まず広告主として登録してください。</p>
      <NuxtLink :to="`/organizations/${orgSlug}/advertiser/register`">
        <Button label="広告主登録" icon="pi pi-user-plus" />
      </NuxtLink>
      <div class="mt-4">
        <NuxtLink :to="`/organizations/${orgSlug}/advertiser/rate-simulator`">
          <Button label="料金シミュレーター" icon="pi pi-calculator" severity="secondary" text />
        </NuxtLink>
      </div>
    </div>

    <!-- 登録済みダッシュボード -->
    <div v-else-if="account">
      <div class="mb-6 flex items-center justify-between">
        <div>
          <PageHeader title="広告主ダッシュボード" />
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
        <SectionCard>
          <p class="text-sm text-surface-500">キャンペーン</p>
          <p class="text-2xl font-bold">{{ overview.activeCampaigns }} <span class="text-sm font-normal text-surface-400">/ {{ overview.totalCampaigns }}</span></p>
        </SectionCard>
        <SectionCard>
          <p class="text-sm text-surface-500">インプレッション</p>
          <p class="text-2xl font-bold">{{ overview.totalImpressions.toLocaleString() }}</p>
        </SectionCard>
        <SectionCard>
          <p class="text-sm text-surface-500">クリック / CTR</p>
          <p class="text-2xl font-bold">{{ overview.totalClicks.toLocaleString() }} <span class="text-sm font-normal text-surface-400">{{ overview.avgCtr }}%</span></p>
        </SectionCard>
        <SectionCard>
          <p class="text-sm text-surface-500">広告費（税抜）</p>
          <p class="text-2xl font-bold">¥{{ overview.totalCost.toLocaleString() }}</p>
          <ProgressBar :value="overview.monthlyBudgetUsedPct" class="mt-2" style="height: 6px" />
          <p class="mt-1 text-xs text-surface-400">予算消化 {{ overview.monthlyBudgetUsedPct }}%</p>
        </SectionCard>
      </div>

      <!-- キャンペーン一覧 -->
      <SectionCard v-if="overview && overview.campaigns.length > 0" title="キャンペーン">
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
              <NuxtLink :to="`/organizations/${orgSlug}/advertiser/campaigns/${data.campaignId}`">
                <Button icon="pi pi-chart-bar" text size="small" />
              </NuxtLink>
            </template>
          </Column>
        </DataTable>
      </SectionCard>

      <!-- F09.19.7 §10.3: アカウント設定 -->
      <SectionCard class="mt-6">
        <template #header>
          <div class="flex items-center justify-between">
            <h2 class="text-lg font-semibold">{{ t('advertising.account_settings.title') }}</h2>
            <Button
              icon="pi pi-pencil"
              :label="t('advertising.account_settings.edit')"
              size="small"
              severity="secondary"
              outlined
              @click="openEditDialog"
            />
          </div>
        </template>
        <dl class="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
          <dt class="text-surface-500">{{ t('advertising.account_settings.company_name') }}</dt>
          <dd>{{ account.companyName }}</dd>
          <dt class="text-surface-500">{{ t('advertising.account_settings.contact_email') }}</dt>
          <dd>{{ account.contactEmail }}</dd>
        </dl>
      </SectionCard>

      <Dialog
        v-model:visible="editDialogVisible"
        modal
        :header="t('advertising.account_settings.title')"
        :style="{ width: '32rem' }"
      >
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('advertising.account_settings.company_name') }}</label>
            <InputText v-model="editForm.companyName" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('advertising.account_settings.contact_email') }}</label>
            <InputText v-model="editForm.contactEmail" type="email" class="w-full" />
          </div>
        </div>
        <template #footer>
          <Button
            :label="t('advertising.account_settings.cancel')"
            severity="secondary"
            text
            @click="editDialogVisible = false"
          />
          <Button
            :label="t('advertising.account_settings.save')"
            icon="pi pi-check"
            :loading="savingAccount"
            :disabled="!editForm.companyName || !editForm.contactEmail"
            @click="saveAccount"
          />
        </template>
      </Dialog>

      <!-- ナビゲーション -->
      <div class="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        <NuxtLink :to="`/organizations/${orgSlug}/advertiser/messaging-campaigns`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
            <i class="pi pi-send mb-2 text-2xl text-primary" />
            <p class="text-sm">{{ $t('advertising.advertiser_crud.nav.messaging_campaigns') }}</p>
          </div>
        </NuxtLink>
        <NuxtLink :to="`/organizations/${orgSlug}/advertiser/operational-campaigns`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700" data-testid="operational-campaigns-tile">
            <i class="pi pi-megaphone mb-2 text-2xl text-primary" />
            <p class="text-sm">{{ $t('advertising.operational_campaigns.entry_tile.title') }}</p>
          </div>
        </NuxtLink>
        <NuxtLink :to="`/organizations/${orgSlug}/advertiser/invoices`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
            <i class="pi pi-file-edit mb-2 text-2xl text-primary" />
            <p class="text-sm">請求書</p>
          </div>
        </NuxtLink>
        <NuxtLink :to="`/organizations/${orgSlug}/advertiser/rate-simulator`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
            <i class="pi pi-calculator mb-2 text-2xl text-primary" />
            <p class="text-sm">料金シミュレーター</p>
          </div>
        </NuxtLink>
        <NuxtLink :to="`/organizations/${orgSlug}/advertiser/report-schedules`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
            <i class="pi pi-calendar-clock mb-2 text-2xl text-primary" />
            <p class="text-sm">定期レポート</p>
          </div>
        </NuxtLink>
        <NuxtLink :to="`/organizations/${orgSlug}/advertiser/credit-limit-requests`">
          <div class="cursor-pointer rounded-lg border border-surface-300 p-3 text-center transition hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-700">
            <i class="pi pi-wallet mb-2 text-2xl text-primary" />
            <p class="text-sm">与信枠申請</p>
          </div>
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
