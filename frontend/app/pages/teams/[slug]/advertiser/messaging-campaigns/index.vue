<script setup lang="ts">
// F09.17 Phase 11-d-4 チームスコープ メッセージ型キャンペーン 一覧
// 組織版 (pages/organizations/[id]/advertiser/messaging-campaigns/index.vue) を複製し
// scope='TEAM' 化したもの。Composable は (scopeType, scopeId) 引数で組織/チーム共通。
import type {
  AdMessagingCampaignListItem,
  AdMessagingCampaignStatus,
  ScopeType,
} from '~/types/adMessagingCampaign'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
// F09.17 Phase 11-d-4: チーム配下ページは scope='TEAM' 固定で composable を呼ぶ。
const scopeType: ScopeType = 'TEAM'
const scopeId = teamSlug
const { t } = useI18n()
const api = useAdMessagingCampaignApi()

const items = ref<AdMessagingCampaignListItem[]>([])
const loading = ref(true)
const page = ref(0)
const size = ref(20)
const totalElements = ref(0)
const activeTab = ref<AdMessagingCampaignStatus | 'ALL'>('ALL')

const tabs: Array<{ value: AdMessagingCampaignStatus | 'ALL'; labelKey: string }> = [
  { value: 'ALL', labelKey: 'advertising.advertiser_crud.list_tab.all' },
  { value: 'DRAFT', labelKey: 'advertising.advertiser_crud.list_tab.draft' },
  { value: 'REVIEW', labelKey: 'advertising.advertiser_crud.list_tab.review' },
  { value: 'APPROVED', labelKey: 'advertising.advertiser_crud.list_tab.approved' },
  { value: 'SCHEDULED', labelKey: 'advertising.advertiser_crud.list_tab.scheduled' },
  { value: 'DELIVERING', labelKey: 'advertising.advertiser_crud.list_tab.delivering' },
  { value: 'PAUSED', labelKey: 'advertising.advertiser_crud.list_tab.paused' },
  { value: 'COMPLETED', labelKey: 'advertising.advertiser_crud.list_tab.completed' },
  { value: 'BLOCKED', labelKey: 'advertising.advertiser_crud.list_tab.blocked' },
  { value: 'CANCELLED', labelKey: 'advertising.advertiser_crud.list_tab.cancelled' },
]

const statusSeverityMap: Record<AdMessagingCampaignStatus, 'success' | 'info' | 'warn' | 'danger' | 'secondary'> = {
  DRAFT: 'secondary',
  REVIEW: 'info',
  APPROVED: 'info',
  SCHEDULED: 'info',
  DELIVERING: 'success',
  PAUSED: 'warn',
  COMPLETED: 'secondary',
  BLOCKED: 'danger',
  CANCELLED: 'secondary',
}

function statusLabel(status: AdMessagingCampaignStatus): string {
  return t(`advertising.status.${status.toLowerCase()}`)
}

async function load() {
  loading.value = true
  try {
    const status = activeTab.value === 'ALL' ? undefined : activeTab.value
    const res = await api.listCampaigns(scopeType, scopeId, {
      status,
      page: page.value,
      size: size.value,
    })
    items.value = res.data
    totalElements.value = res.meta.totalElements
  }
  catch {
    items.value = []
  }
  finally {
    loading.value = false
  }
}

function onTabChange(v: AdMessagingCampaignStatus | 'ALL') {
  activeTab.value = v
  page.value = 0
  load()
}

function onPageChange(event: { page: number; rows: number }) {
  page.value = event.page
  size.value = event.rows
  load()
}

function formatPeriod(item: AdMessagingCampaignListItem): string {
  return `${item.startsAt.slice(0, 10)} - ${item.endsAt.slice(0, 10)}`
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('advertising.pages.advertiser_campaign_list.title')" />
      <NuxtLink :to="`/teams/${teamSlug}/advertiser/messaging-campaigns/new`">
        <Button
          icon="pi pi-plus"
          :label="t('advertising.advertiser_crud.list_action.create_button')"
        />
      </NuxtLink>
    </div>

    <p class="mb-4 text-sm text-surface-500">
      {{ t('advertising.pages.advertiser_campaign_list.description') }}
    </p>

    <!-- status タブ -->
    <div class="mb-4 flex flex-wrap gap-2">
      <Button
        v-for="tab in tabs"
        :key="tab.value"
        :label="t(tab.labelKey)"
        size="small"
        :severity="activeTab === tab.value ? 'primary' : 'secondary'"
        :outlined="activeTab !== tab.value"
        @click="onTabChange(tab.value)"
      />
    </div>

    <SectionCard>
      <DataTable
        :value="items"
        :loading="loading"
        :rows="size"
        :total-records="totalElements"
        striped-rows
        paginator
        lazy
        :first="page * size"
        @page="onPageChange"
      >
        <template #empty>
          <DashboardEmptyState
            :message="t('advertising.pages.advertiser_campaign_list.empty')"
          />
        </template>

        <Column field="name" :header="t('advertising.advertiser_crud.list_table.name')">
          <template #body="{ data }">
            <NuxtLink
              :to="`/teams/${teamSlug}/advertiser/messaging-campaigns/${data.id}`"
              class="text-primary hover:underline"
            >
              {{ data.name }}
            </NuxtLink>
          </template>
        </Column>
        <Column :header="t('advertising.advertiser_crud.list_table.status')">
          <template #body="{ data }">
            <Tag
              :value="statusLabel(data.status)"
              :severity="statusSeverityMap[data.status as AdMessagingCampaignStatus]"
            />
          </template>
        </Column>
        <Column :header="t('advertising.advertiser_crud.list_table.period')">
          <template #body="{ data }">{{ formatPeriod(data) }}</template>
        </Column>
        <Column
          field="totalBudgetYen"
          :header="t('advertising.advertiser_crud.list_table.total_budget')"
          class="text-right"
        >
          <template #body="{ data }">¥{{ data.totalBudgetYen.toLocaleString() }}</template>
        </Column>
        <Column
          field="consumedBudgetYen"
          :header="t('advertising.advertiser_crud.list_table.consumed_budget')"
          class="text-right"
        >
          <template #body="{ data }">¥{{ data.consumedBudgetYen.toLocaleString() }}</template>
        </Column>
        <Column :header="t('advertising.advertiser_crud.list_table.actions')">
          <template #body="{ data }">
            <NuxtLink :to="`/teams/${teamSlug}/advertiser/messaging-campaigns/${data.id}`">
              <Button icon="pi pi-arrow-right" text size="small" />
            </NuxtLink>
          </template>
        </Column>
      </DataTable>
    </SectionCard>
  </div>
</template>
