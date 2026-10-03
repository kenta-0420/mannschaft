<script setup lang="ts">
import type { AdChannelType } from '~/types/adMessagingCampaign'
import type { AdDelivery } from '~/types/adPreferences'

/**
 * F09.17 受信者向け広告配信履歴ページ
 *
 * 機能:
 * - 過去 90 日分の配信履歴一覧（campaign 名 / 配信日時 / チャネル / 開封・クリック）
 * - チャネルフィルタ + カーソルベースのページング
 * - 通報ボタン → AdReportModal
 * - GDPR 削除ボタン → DELETE /api/v1/me/ad-deliveries（冪等）
 */

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const notification = useNotification()
const { formatDateTime } = useDatetime()
const confirm = useConfirm()
const deliveriesApi = useAdDeliveriesApi()

const items = ref<AdDelivery[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const loadingMore = ref(false)
const deletingAll = ref(false)

const filterChannel = ref<AdChannelType | 'ALL'>('ALL')

const reportModalVisible = ref(false)
const reportCampaignId = ref<string | null>(null)

async function loadInitial() {
  loading.value = true
  try {
    const res = await deliveriesApi.listDeliveries({
      channelType: filterChannel.value === 'ALL' ? undefined : filterChannel.value,
      limit: 20,
    })
    items.value = res.data
    nextCursor.value = res.meta.nextCursor
  } catch (err) {
    notification.error(t('advertising.pages.me_ad_deliveries.title'), String(err))
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const res = await deliveriesApi.listDeliveries({
      channelType: filterChannel.value === 'ALL' ? undefined : filterChannel.value,
      cursor: nextCursor.value,
      limit: 20,
    })
    items.value = [...items.value, ...res.data]
    nextCursor.value = res.meta.nextCursor
  } catch (err) {
    notification.error(t('advertising.pages.me_ad_deliveries.title'), String(err))
  } finally {
    loadingMore.value = false
  }
}

function selectFilter(channel: AdChannelType | 'ALL') {
  filterChannel.value = channel
  loadInitial()
}

function openReportModal(item: AdDelivery) {
  reportCampaignId.value = item.campaignId
  reportModalVisible.value = true
}

function handleDeleteAll() {
  confirm.require({
    message: t('advertising.pages.me_ad_deliveries.delete_all_confirm'),
    header: t('advertising.pages.me_ad_deliveries.delete_all_section'),
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: t('advertising.report_dialog.cancel'),
    acceptLabel: t('advertising.pages.me_ad_deliveries.delete_all_button'),
    acceptClass: 'p-button-danger',
    accept: async () => {
      deletingAll.value = true
      try {
        await deliveriesApi.deleteAllDeliveries()
        items.value = []
        nextCursor.value = null
        notification.success(
          t('advertising.pages.me_ad_deliveries.delete_all_success'),
        )
      } catch (err) {
        notification.error(
          t('advertising.pages.me_ad_deliveries.delete_all_section'),
          String(err),
        )
      } finally {
        deletingAll.value = false
      }
    },
  })
}

function engagementLabel(item: AdDelivery): string {
  if (item.clickedAt) return t('advertising.pages.me_ad_deliveries.engagement_clicked')
  if (item.openedAt) return t('advertising.pages.me_ad_deliveries.engagement_read')
  return t('advertising.pages.me_ad_deliveries.engagement_none')
}


onMounted(loadInitial)
</script>

<template>
  <div>
    <PageHeader :title="t('advertising.pages.me_ad_deliveries.title')" back-to="/settings" />

    <div class="mx-auto max-w-4xl">
      <p class="mb-4 text-sm text-surface-500 dark:text-surface-300">
        {{ t('advertising.pages.me_ad_deliveries.description') }}
      </p>

      <!-- フィルタ -->
      <div class="mb-4 flex flex-wrap gap-2">
        <Button
          :label="t('advertising.pages.me_ad_deliveries.filter_all')"
          :severity="filterChannel === 'ALL' ? 'primary' : 'secondary'"
          :outlined="filterChannel !== 'ALL'"
          size="small"
          @click="selectFilter('ALL')"
        />
        <Button
          :label="t('advertising.pages.me_ad_deliveries.filter_announcement')"
          :severity="filterChannel === 'ANNOUNCEMENT' ? 'primary' : 'secondary'"
          :outlined="filterChannel !== 'ANNOUNCEMENT'"
          size="small"
          @click="selectFilter('ANNOUNCEMENT')"
        />
        <Button
          :label="t('advertising.pages.me_ad_deliveries.filter_email')"
          :severity="filterChannel === 'EMAIL' ? 'primary' : 'secondary'"
          :outlined="filterChannel !== 'EMAIL'"
          size="small"
          @click="selectFilter('EMAIL')"
        />
        <Button
          :label="t('advertising.pages.me_ad_deliveries.filter_push')"
          :severity="filterChannel === 'PUSH' ? 'primary' : 'secondary'"
          :outlined="filterChannel !== 'PUSH'"
          size="small"
          @click="selectFilter('PUSH')"
        />
        <Button
          :label="t('advertising.pages.me_ad_deliveries.filter_banner')"
          :severity="filterChannel === 'BANNER' ? 'primary' : 'secondary'"
          :outlined="filterChannel !== 'BANNER'"
          size="small"
          @click="selectFilter('BANNER')"
        />
      </div>

      <PageLoading v-if="loading" />

      <DashboardEmptyState
        v-else-if="items.length === 0"
        :message="t('advertising.pages.me_ad_deliveries.empty')"
      />

      <SectionCard v-else class="mb-6">
        <DataTable :value="items" responsive-layout="scroll" data-testid="deliveries-table">
          <Column :header="t('advertising.pages.me_ad_deliveries.column_campaign')">
            <template #body="{ data }: { data: AdDelivery }">
              <div class="flex items-center gap-2">
                <AdLabelBadge size="sm" />
                <span>{{ data.subject ?? '—' }}</span>
              </div>
            </template>
          </Column>
          <Column
            field="advertiserAccountId"
            :header="t('advertising.pages.me_ad_deliveries.column_advertiser')"
          >
            <template #body="{ data }: { data: AdDelivery }">
              #{{ data.advertiserAccountId }}
            </template>
          </Column>
          <Column :header="t('advertising.pages.me_ad_deliveries.column_channel')">
            <template #body="{ data }: { data: AdDelivery }">
              {{ t(`advertising.channel.${data.channelType.toLowerCase()}`) }}
            </template>
          </Column>
          <Column :header="t('advertising.pages.me_ad_deliveries.delivered_at')">
            <template #body="{ data }: { data: AdDelivery }">
              {{ formatDateTime(data.deliveredAt) }}
            </template>
          </Column>
          <Column :header="t('advertising.pages.me_ad_deliveries.column_engagement')">
            <template #body="{ data }: { data: AdDelivery }">
              {{ engagementLabel(data) }}
            </template>
          </Column>
          <Column :header="t('advertising.pages.me_ad_deliveries.column_actions')">
            <template #body="{ data }: { data: AdDelivery }">
              <Button
                icon="pi pi-flag"
                :label="t('advertising.actions.report')"
                size="small"
                severity="secondary"
                outlined
                data-testid="report-button"
                @click="openReportModal(data)"
              />
            </template>
          </Column>
        </DataTable>

        <div v-if="nextCursor" class="mt-4 flex justify-center">
          <Button
            :label="t('advertising.pages.me_ad_deliveries.load_more')"
            :loading="loadingMore"
            outlined
            data-testid="load-more-button"
            @click="loadMore"
          />
        </div>
      </SectionCard>

      <!-- GDPR 削除 -->
      <SectionCard>
        <template #header>
          <h2 class="text-base font-semibold">
            {{ t('advertising.pages.me_ad_deliveries.delete_all_section') }}
          </h2>
        </template>
        <p class="mb-4 text-xs text-surface-500 dark:text-surface-300">
          {{ t('advertising.pages.me_ad_deliveries.delete_all_description') }}
        </p>
        <Button
          :label="t('advertising.pages.me_ad_deliveries.delete_all_button')"
          icon="pi pi-trash"
          severity="danger"
          :loading="deletingAll"
          data-testid="delete-all-button"
          @click="handleDeleteAll"
        />
      </SectionCard>
    </div>

    <AdReportModal
      v-if="reportCampaignId"
      v-model:visible="reportModalVisible"
      :campaign-id="reportCampaignId"
    />
  </div>
</template>
