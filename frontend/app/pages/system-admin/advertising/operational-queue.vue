<script setup lang="ts">
/**
 * F09.19.4b SYSTEM_ADMIN 運用型キャンペーン（バナー広告）審査キュー。
 *
 * <p>設計書 §8.5 / 受け入れ条件 §16 F09.19.4b。既定 PENDING_REVIEW の一覧を表示し、
 * 行選択で詳細（キャンペーン設定 + 広告主名・scope + クリエイティブ一覧）を確認して
 * approve（承認）/ reject（差戻し・理由必須 1〜500 文字）を行う。</p>
 */
import type { OperationalCampaign } from '~/composables/useOperationalCampaignApi'
import type { OperationalCampaignReviewDetail } from '~/composables/useSystemAdminOperationalCampaignApi'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const api = useSystemAdminOperationalCampaignApi()
const toast = useNotification()
const confirm = useConfirm()
const router = useRouter()

const items = ref<OperationalCampaign[]>([])
const loading = ref(true)
const page = ref(0)
const size = ref(20)
const total = ref(0)

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<OperationalCampaignReviewDetail | null>(null)
const processing = ref(false)
const rejectMode = ref(false)
const rejectReason = ref('')

function formatYen(value: number | undefined): string {
  return `¥${Math.round(value ?? 0).toLocaleString()}`
}

function formatPeriod(item: { startDate?: string; endDate?: string }): string {
  const start = item.startDate ?? '—'
  const end = item.endDate ?? t('advertising.operational_campaigns.list.no_end_date')
  return `${start} 〜 ${end}`
}

async function load() {
  loading.value = true
  try {
    const res = await api.listQueue({ status: 'PENDING_REVIEW', page: page.value, size: size.value })
    items.value = res.data ?? []
    total.value = res.meta?.total ?? 0
  }
  catch {
    items.value = []
    toast.error(t('advertising.operational_campaigns.review.load_failed'))
  }
  finally {
    loading.value = false
  }
}

function onPageChange(event: { page: number; rows: number }) {
  page.value = event.page
  size.value = event.rows
  load()
}

async function openDetail(item: OperationalCampaign) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  rejectMode.value = false
  rejectReason.value = ''
  try {
    const res = await api.getDetail(String(item.id))
    detail.value = res.data
  }
  catch {
    toast.error(t('advertising.operational_campaigns.review.detail_load_failed'))
    detailOpen.value = false
  }
  finally {
    detailLoading.value = false
  }
}

function approve() {
  if (!detail.value) return
  const id = String(detail.value.id)
  confirm.require({
    header: t('advertising.operational_campaigns.review.approve'),
    message: t('advertising.operational_campaigns.review.approve_confirm'),
    icon: 'pi pi-check-circle',
    accept: async () => {
      processing.value = true
      try {
        await api.approve(id)
        toast.success(t('advertising.operational_campaigns.review.approved_toast'))
        detailOpen.value = false
        await load()
      }
      catch {
        toast.error(t('advertising.operational_campaigns.review.action_failed'))
      }
      finally {
        processing.value = false
      }
    },
  })
}

async function submitReject() {
  if (!detail.value) return
  const reason = rejectReason.value.trim()
  if (reason.length < 1 || reason.length > 500) return
  processing.value = true
  try {
    await api.reject(String(detail.value.id), reason)
    toast.success(t('advertising.operational_campaigns.review.rejected_toast'))
    detailOpen.value = false
    await load()
  }
  catch {
    toast.error(t('advertising.operational_campaigns.review.action_failed'))
  }
  finally {
    processing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-screen-xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <Button
          :label="t('advertising.operational_campaigns.review.back_to_dashboard')"
          icon="pi pi-arrow-left"
          severity="secondary"
          text
          class="mb-2"
          @click="router.push('/system-admin/advertising')"
        />
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ t('advertising.operational_campaigns.review.title') }}
        </h1>
        <p class="mt-0.5 text-sm text-surface-500">
          {{ t('advertising.operational_campaigns.review.description') }}
        </p>
      </div>
      <Button
        v-tooltip.left="t('advertising.actions.reload')"
        icon="pi pi-refresh"
        text
        rounded
        :loading="loading"
        @click="load"
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
        data-testid="operational-review-table"
        @page="onPageChange"
      >
        <template #empty>
          <div class="py-10 text-center text-surface-500" data-testid="operational-review-empty">
            {{ t('advertising.operational_campaigns.review.empty') }}
          </div>
        </template>

        <Column field="name" :header="t('advertising.operational_campaigns.list.col.name')" />
        <Column :header="t('advertising.operational_campaigns.list.col.pricing_model')">
          <template #body="{ data }"><Tag :value="data.pricingModel" severity="secondary" /></template>
        </Column>
        <Column :header="t('advertising.operational_campaigns.list.col.daily_budget')" class="text-right">
          <template #body="{ data }">{{ formatYen(data.dailyBudget) }}</template>
        </Column>
        <Column :header="t('advertising.operational_campaigns.list.col.period')">
          <template #body="{ data }"><span class="text-sm">{{ formatPeriod(data) }}</span></template>
        </Column>
        <Column :header="t('advertising.operational_campaigns.list.col.actions')">
          <template #body="{ data }">
            <Button
              :label="t('advertising.operational_campaigns.review.open_detail')"
              icon="pi pi-eye"
              size="small"
              outlined
              data-testid="operational-review-open"
              @click="openDetail(data)"
            />
          </template>
        </Column>
      </DataTable>
    </SectionCard>

    <!-- 審査詳細ダイアログ -->
    <Dialog
      v-model:visible="detailOpen"
      modal
      :header="t('advertising.operational_campaigns.review.detail_title')"
      class="w-full max-w-3xl"
      data-testid="operational-review-detail"
    >
      <div v-if="detailLoading" class="flex justify-center py-10">
        <LoadingBounce />
      </div>

      <div v-else-if="detail" class="space-y-4">
        <!-- キャンペーン設定 + 広告主帰属 -->
        <SectionCard>
          <h3 class="mb-3 text-base font-semibold">
            {{ detail.name }}
          </h3>
          <dl class="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
            <div>
              <dt class="text-surface-500">{{ t('advertising.operational_campaigns.review.advertiser') }}</dt>
              <dd class="font-medium" data-testid="review-advertiser-name">{{ detail.advertiserName }}</dd>
            </div>
            <div>
              <dt class="text-surface-500">{{ t('advertising.operational_campaigns.review.scope') }}</dt>
              <dd>
                <Tag
                  :value="detail.scopeType === 'ORGANIZATION'
                    ? t('advertising.operational_campaigns.review.scope_organization')
                    : t('advertising.operational_campaigns.review.scope_team')"
                  severity="info"
                />
                <span class="ml-1 text-xs text-surface-400">#{{ detail.scopeId }}</span>
              </dd>
            </div>
            <div>
              <dt class="text-surface-500">{{ t('advertising.operational_campaigns.list.col.pricing_model') }}</dt>
              <dd><Tag :value="detail.pricingModel" severity="secondary" /></dd>
            </div>
            <div>
              <dt class="text-surface-500">{{ t('advertising.operational_campaigns.review.unit_price') }}</dt>
              <dd>{{ formatYen(detail.unitPriceSnapshot) }}</dd>
            </div>
            <div>
              <dt class="text-surface-500">{{ t('advertising.operational_campaigns.list.col.daily_budget') }}</dt>
              <dd>{{ formatYen(detail.dailyBudget) }}</dd>
            </div>
            <div>
              <dt class="text-surface-500">{{ t('advertising.operational_campaigns.list.col.period') }}</dt>
              <dd>{{ formatPeriod(detail) }}</dd>
            </div>
          </dl>
        </SectionCard>

        <!-- クリエイティブ一覧 -->
        <SectionCard>
          <h3 class="mb-3 text-base font-semibold">
            {{ t('advertising.operational_campaigns.review.creatives') }}
          </h3>
          <div
            v-if="!detail.creatives || detail.creatives.length === 0"
            class="py-4 text-center text-sm text-surface-500"
            data-testid="review-no-creatives"
          >
            {{ t('advertising.operational_campaigns.review.no_creatives') }}
          </div>
          <div v-else class="space-y-3" data-testid="review-creatives-list">
            <div
              v-for="creative in detail.creatives"
              :key="creative.id"
              class="flex items-start gap-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
            >
              <img
                v-if="creative.imageUrl"
                :src="creative.imageUrl"
                :alt="creative.altText ?? creative.title ?? ''"
                class="h-16 w-28 shrink-0 rounded object-cover"
              >
              <div v-else class="flex h-16 w-28 shrink-0 items-center justify-center rounded bg-surface-100 text-surface-400 dark:bg-surface-700">
                <i class="pi pi-image text-xl" />
              </div>
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-2">
                  <span class="truncate font-medium">{{ creative.title ?? '—' }}</span>
                  <Tag v-if="creative.status" :value="creative.status" severity="secondary" />
                </div>
                <a
                  v-if="creative.destinationUrl"
                  :href="creative.destinationUrl"
                  target="_blank"
                  rel="noopener"
                  class="mt-1 block truncate text-xs text-primary hover:underline"
                >
                  {{ creative.destinationUrl }}
                </a>
              </div>
            </div>
          </div>
        </SectionCard>

        <!-- 差戻し理由入力（reject モード時のみ・モーダル on モーダル回避のためインライン） -->
        <SectionCard v-if="rejectMode">
          <label class="mb-1 block text-sm font-medium">
            {{ t('advertising.operational_campaigns.review.reject_reason_label') }}
            <span class="text-red-500">*</span>
          </label>
          <Textarea
            v-model="rejectReason"
            rows="4"
            class="w-full"
            :maxlength="500"
            :placeholder="t('advertising.operational_campaigns.review.reject_reason_placeholder')"
            data-testid="review-reject-reason"
          />
          <p class="mt-1 text-xs text-surface-400">{{ rejectReason.trim().length }} / 500</p>
        </SectionCard>
      </div>

      <template #footer>
        <template v-if="detail && !rejectMode">
          <Button
            :label="t('advertising.operational_campaigns.review.reject')"
            severity="danger"
            icon="pi pi-times"
            outlined
            :disabled="processing"
            data-testid="review-reject-start"
            @click="rejectMode = true"
          />
          <Button
            :label="t('advertising.operational_campaigns.review.approve')"
            severity="success"
            icon="pi pi-check"
            :loading="processing"
            data-testid="review-approve"
            @click="approve"
          />
        </template>
        <template v-else-if="detail && rejectMode">
          <Button
            :label="t('advertising.operational_campaigns.form.cancel')"
            severity="secondary"
            text
            :disabled="processing"
            @click="rejectMode = false"
          />
          <Button
            :label="t('advertising.operational_campaigns.review.reject_submit')"
            severity="danger"
            icon="pi pi-ban"
            :loading="processing"
            :disabled="rejectReason.trim().length < 1 || rejectReason.trim().length > 500"
            data-testid="review-reject-submit"
            @click="submitReject"
          />
        </template>
      </template>
    </Dialog>
  </div>
</template>
