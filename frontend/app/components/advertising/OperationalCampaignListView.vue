<script setup lang="ts">
/**
 * F09.19.4b 運用型キャンペーン（バナー広告）一覧ビュー（広告主向け・org/team 共通）。
 *
 * <p>設計書 §8.5 / 受け入れ条件 §16 F09.19.4b。組織版・チーム版の両ページから
 * scopeType / scopeId / basePrefix を渡して同一コンポーネントを流用する。</p>
 */
import type { ScopeType } from '~/types/adMessagingCampaign'
import type {
  OperationalCampaign,
  OperationalCampaignStatus,
} from '~/composables/useOperationalCampaignApi'

const props = defineProps<{
  scopeType: ScopeType
  /** 組織/チームの識別子（route.params.slug。API パスに直接使用）。 */
  scopeId: string
  /** 導線用のベースパス（例: /organizations/xxx/advertiser）。 */
  basePrefix: string
}>()

const { t } = useI18n()
const api = useOperationalCampaignApi()
const toast = useNotification()
const confirm = useConfirm()

const items = ref<OperationalCampaign[]>([])
const loading = ref(true)
const page = ref(0)
const size = ref(20)
const total = ref(0)
const transitioningId = ref<number | null>(null)
const activeTab = ref<OperationalCampaignStatus | 'ALL'>('ALL')
const showGuide = ref(false)

const tabs: Array<{ value: OperationalCampaignStatus | 'ALL'; labelKey: string }> = [
  { value: 'ALL', labelKey: 'advertising.operational_campaigns.list.tab.all' },
  { value: 'DRAFT', labelKey: 'advertising.operational_campaigns.list.tab.draft' },
  { value: 'PENDING_REVIEW', labelKey: 'advertising.operational_campaigns.list.tab.pending_review' },
  { value: 'ACTIVE', labelKey: 'advertising.operational_campaigns.list.tab.active' },
  { value: 'PAUSED', labelKey: 'advertising.operational_campaigns.list.tab.paused' },
  { value: 'ENDED', labelKey: 'advertising.operational_campaigns.list.tab.ended' },
]

const statusSeverityMap: Record<OperationalCampaignStatus, 'success' | 'info' | 'warn' | 'secondary'> = {
  DRAFT: 'secondary',
  PENDING_REVIEW: 'info',
  ACTIVE: 'success',
  PAUSED: 'warn',
  ENDED: 'secondary',
}

function statusLabel(status: OperationalCampaignStatus): string {
  return t(`advertising.operational_campaigns.status.${status.toLowerCase()}`)
}

function formatPeriod(item: OperationalCampaign): string {
  const start = item.startDate ?? '—'
  const end = item.endDate ?? t('advertising.operational_campaigns.list.no_end_date')
  return `${start} 〜 ${end}`
}

function formatYen(value: number | undefined): string {
  return `¥${Math.round(value ?? 0).toLocaleString()}`
}

async function load() {
  loading.value = true
  try {
    const status = activeTab.value === 'ALL' ? undefined : activeTab.value
    const res = await api.listCampaigns(props.scopeType, props.scopeId, {
      status,
      page: page.value,
      size: size.value,
    })
    items.value = res.data ?? []
    total.value = res.meta?.total ?? 0
  }
  catch {
    items.value = []
    toast.error(t('advertising.operational_campaigns.errors.load_failed'))
  }
  finally {
    loading.value = false
  }
}

function onTabChange(v: OperationalCampaignStatus | 'ALL') {
  activeTab.value = v
  page.value = 0
  load()
}

function onPageChange(event: { page: number; rows: number }) {
  page.value = event.page
  size.value = event.rows
  load()
}

/** 状態遷移の実行（確認ダイアログ付き）。 */
function runTransition(
  item: OperationalCampaign,
  action: 'submit' | 'pause' | 'resume' | 'end',
) {
  confirm.require({
    message: t(`advertising.operational_campaigns.confirm.${action}`),
    header: t(`advertising.operational_campaigns.actions.${action}`),
    icon: 'pi pi-exclamation-triangle',
    accept: async () => {
      const id = String(item.id)
      transitioningId.value = item.id ?? null
      try {
        const fn = (() => {
          switch (action) {
            case 'submit': return api.submitCampaign
            case 'pause': return api.pauseCampaign
            case 'resume': return api.resumeCampaign
            case 'end': return api.endCampaign
          }
        })()
        await fn(props.scopeType, props.scopeId, id)
        toast.success(t(`advertising.operational_campaigns.toast.${action}`))
        await load()
      }
      catch {
        toast.error(t('advertising.operational_campaigns.errors.transition_failed'))
      }
      finally {
        transitioningId.value = null
      }
    },
  })
}

function canSubmit(item: OperationalCampaign): boolean {
  return item.status === 'DRAFT'
}
function canEdit(item: OperationalCampaign): boolean {
  return item.status === 'DRAFT' || item.status === 'PAUSED'
}
function canPause(item: OperationalCampaign): boolean {
  return item.status === 'ACTIVE'
}
function canResume(item: OperationalCampaign): boolean {
  // 通報自動停止中（reportSuspendedAt 非 null）は resume 不可
  return item.status === 'PAUSED' && !item.reportSuspendedAt
}
function canEnd(item: OperationalCampaign): boolean {
  return item.status === 'ACTIVE' || item.status === 'PAUSED'
}
function isSuspended(item: OperationalCampaign): boolean {
  return !!item.reportSuspendedAt
}
function showRejectAlert(item: OperationalCampaign): boolean {
  return item.status === 'DRAFT' && !!item.rejectReason
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader
        :title="t('advertising.operational_campaigns.title')"
        help
        @help="showGuide = true"
      >
        <template #actions>
          <NuxtLink :to="`${basePrefix}/operational-campaigns/new`">
            <Button
              icon="pi pi-plus"
              :label="t('advertising.operational_campaigns.list.create_button')"
              data-testid="operational-campaign-create"
            />
          </NuxtLink>
        </template>
      </PageHeader>
    </div>

    <p class="mb-4 text-sm text-surface-500">
      {{ t('advertising.operational_campaigns.list.description') }}
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
        :total-records="total"
        striped-rows
        paginator
        lazy
        :first="page * size"
        data-testid="operational-campaign-table"
        @page="onPageChange"
      >
        <template #empty>
          <div class="py-10 text-center" data-testid="operational-campaign-empty">
            <i class="pi pi-megaphone mb-3 text-4xl text-surface-300" />
            <p class="mb-4 text-surface-500">
              {{ t('advertising.operational_campaigns.list.empty') }}
            </p>
            <NuxtLink :to="`${basePrefix}/operational-campaigns/new`">
              <Button
                icon="pi pi-plus"
                :label="t('advertising.operational_campaigns.list.create_button')"
                severity="primary"
                outlined
              />
            </NuxtLink>
          </div>
        </template>

        <Column field="name" :header="t('advertising.operational_campaigns.list.col.name')">
          <template #body="{ data }">
            <div>
              <span class="font-medium">{{ data.name }}</span>
              <span
                v-if="isSuspended(data)"
                class="ml-2 inline-flex items-center rounded bg-red-100 px-2 py-0.5 text-xs font-semibold text-red-700 dark:bg-red-900/40 dark:text-red-200"
                data-testid="report-suspended-badge"
              >
                <i class="pi pi-flag mr-1 text-xs" />
                {{ t('advertising.operational_campaigns.list.report_suspended_badge') }}
              </span>
              <!-- 差戻し理由（DRAFT かつ rejectReason 非 null）警告色インラインアラート -->
              <Message
                v-if="showRejectAlert(data)"
                severity="warn"
                :closable="false"
                class="mt-2"
                data-testid="reject-reason-alert"
              >
                <span class="text-xs font-semibold">
                  {{ t('advertising.operational_campaigns.list.reject_reason_label') }}:
                </span>
                <span class="text-xs">{{ data.rejectReason }}</span>
              </Message>
            </div>
          </template>
        </Column>

        <Column :header="t('advertising.operational_campaigns.list.col.status')">
          <template #body="{ data }">
            <Tag
              :value="statusLabel(data.status)"
              :severity="statusSeverityMap[data.status as OperationalCampaignStatus]"
            />
          </template>
        </Column>

        <Column :header="t('advertising.operational_campaigns.list.col.pricing_model')">
          <template #body="{ data }">
            <Tag :value="data.pricingModel" severity="secondary" />
          </template>
        </Column>

        <Column :header="t('advertising.operational_campaigns.list.col.daily_budget')" class="text-right">
          <template #body="{ data }">{{ formatYen(data.dailyBudget) }}</template>
        </Column>

        <Column :header="t('advertising.operational_campaigns.list.col.period')">
          <template #body="{ data }">
            <span class="text-sm">{{ formatPeriod(data) }}</span>
          </template>
        </Column>

        <Column :header="t('advertising.operational_campaigns.list.col.unit_price')" class="text-right">
          <template #body="{ data }">{{ formatYen(data.unitPriceSnapshot) }}</template>
        </Column>

        <Column :header="t('advertising.operational_campaigns.list.col.actions')">
          <template #body="{ data }">
            <div class="flex flex-wrap items-center gap-1">
              <Button
                v-if="canSubmit(data)"
                v-tooltip.top="t('advertising.operational_campaigns.actions.submit')"
                icon="pi pi-send"
                size="small"
                text
                :loading="transitioningId === data.id"
                data-testid="action-submit"
                @click="runTransition(data, 'submit')"
              />
              <Button
                v-if="canPause(data)"
                v-tooltip.top="t('advertising.operational_campaigns.actions.pause')"
                icon="pi pi-pause"
                severity="warn"
                size="small"
                text
                :loading="transitioningId === data.id"
                data-testid="action-pause"
                @click="runTransition(data, 'pause')"
              />
              <Button
                v-if="canResume(data)"
                v-tooltip.top="t('advertising.operational_campaigns.actions.resume')"
                icon="pi pi-play"
                severity="success"
                size="small"
                text
                :loading="transitioningId === data.id"
                data-testid="action-resume"
                @click="runTransition(data, 'resume')"
              />
              <Button
                v-if="canEnd(data)"
                v-tooltip.top="t('advertising.operational_campaigns.actions.end')"
                icon="pi pi-stop-circle"
                severity="danger"
                size="small"
                text
                :loading="transitioningId === data.id"
                data-testid="action-end"
                @click="runTransition(data, 'end')"
              />
              <NuxtLink v-if="canEdit(data)" :to="`${basePrefix}/operational-campaigns/${data.id}/edit`">
                <Button
                  v-tooltip.top="t('advertising.operational_campaigns.actions.edit')"
                  icon="pi pi-pencil"
                  size="small"
                  text
                  data-testid="action-edit"
                />
              </NuxtLink>
              <NuxtLink :to="`${basePrefix}/creatives`">
                <Button
                  v-tooltip.top="t('advertising.operational_campaigns.list.link_creatives')"
                  icon="pi pi-images"
                  size="small"
                  text
                  severity="secondary"
                />
              </NuxtLink>
              <NuxtLink :to="`${basePrefix}/campaigns/${data.id}`">
                <Button
                  v-tooltip.top="t('advertising.operational_campaigns.list.link_report')"
                  icon="pi pi-chart-bar"
                  size="small"
                  text
                  severity="secondary"
                />
              </NuxtLink>
            </div>
          </template>
        </Column>
      </DataTable>
    </SectionCard>

    <!-- 使い方モーダル -->
    <OperationalCampaignGuideModal v-model:visible="showGuide" />
  </div>
</template>
