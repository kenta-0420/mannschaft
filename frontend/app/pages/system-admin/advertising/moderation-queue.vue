<script setup lang="ts">
/**
 * F09.17 Phase 11-c-4 — SYSTEM_ADMIN 広告キャンペーン審査キュー。
 *
 * <p>`moderation_status IN (PENDING, AUTO_FLAGGED, AUTO_PASSED)` のキャンペーンを
 * カード形式（{@link AdCampaignReviewCard}）で並べ、提出時刻・自動フラグ理由で
 * 絞り込みできる。クリックで詳細ページへ遷移する。</p>
 */
import type { AdReviewQueueItem } from '~/types/adModeration'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const router = useRouter()
const systemAdminAdApi = useSystemAdminAdCampaignApi()
const notification = useNotification()

const loading = ref(true)
const items = ref<AdReviewQueueItem[]>([])
const totalElements = ref(0)
const page = ref(0)
const pageSize = ref(20)

/** 自動フラグ絞り込み */
type AutoFlagFilter = 'ALL' | 'AUTO_FLAGGED' | 'AUTO_PASSED'
const autoFlagFilter = ref<AutoFlagFilter>('ALL')

/** 提出時刻順 (新しい順/古い順) */
type SortOrder = 'NEWEST' | 'OLDEST'
const sortOrder = ref<SortOrder>('OLDEST')

const autoFlagOptions = computed(() => [
  { label: t('advertising.pages.system_admin_moderation.filter_auto_flag_all'), value: 'ALL' as const },
  { label: t('advertising.pages.system_admin_moderation.filter_auto_flag_only'), value: 'AUTO_FLAGGED' as const },
  { label: t('advertising.pages.system_admin_moderation.filter_auto_flag_passed'), value: 'AUTO_PASSED' as const },
])

const filteredItems = computed(() => {
  const filtered = autoFlagFilter.value === 'ALL'
    ? items.value
    : items.value.filter((item) => item.moderationStatus === autoFlagFilter.value)
  const sorted = [...filtered].sort((a, b) => {
    const ta = new Date(a.createdAt).getTime()
    const tb = new Date(b.createdAt).getTime()
    return sortOrder.value === 'NEWEST' ? tb - ta : ta - tb
  })
  return sorted
})

async function load() {
  loading.value = true
  try {
    const res = await systemAdminAdApi.listReviewQueue({
      page: page.value,
      size: pageSize.value,
    })
    items.value = res.data
    totalElements.value = res.meta.totalElements
  } catch {
    notification.error(t('advertising.pages.system_admin_dashboard.load_failed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)

function handleOpen(campaignId: string) {
  router.push(`/system-admin/advertising/moderation-queue/${campaignId}`)
}

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  pageSize.value = event.rows
  load()
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ t('advertising.pages.system_admin_moderation.queue_title') }}
        </h1>
        <p class="mt-0.5 text-sm text-surface-500">
          {{ t('advertising.pages.system_admin_moderation.queue_description') }}
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

    <!-- フィルタ -->
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <div>
        <label class="mb-1 block text-xs font-medium text-surface-600 dark:text-surface-300">
          {{ t('advertising.pages.system_admin_moderation.filter_auto_flag') }}
        </label>
        <SelectButton
          v-model="autoFlagFilter"
          :options="autoFlagOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          data-testid="filter-auto-flag"
        />
      </div>
      <div>
        <label class="mb-1 block text-xs font-medium text-surface-600 dark:text-surface-300">
          {{ t('advertising.pages.system_admin_moderation.filter_submitted_period') }}
        </label>
        <SelectButton
          v-model="sortOrder"
          :options="[
            { label: '↑', value: 'OLDEST' },
            { label: '↓', value: 'NEWEST' },
          ]"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          data-testid="filter-sort-order"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <div
        v-if="filteredItems.length === 0"
        class="rounded-lg border border-dashed border-surface-300 bg-surface-50 px-6 py-12 text-center text-sm text-surface-500 dark:border-surface-700 dark:bg-surface-800"
        data-testid="queue-empty"
      >
        {{ t('advertising.pages.system_admin_moderation.empty') }}
      </div>

      <div
        v-else
        class="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3"
        data-testid="queue-list"
      >
        <AdCampaignReviewCard
          v-for="item in filteredItems"
          :key="item.campaignId"
          :item="item"
          @open="handleOpen"
        />
      </div>

      <div v-if="totalElements > pageSize" class="mt-6 flex justify-center">
        <Paginator
          :rows="pageSize"
          :total-records="totalElements"
          :rows-per-page-options="[10, 20, 50]"
          @page="onPage"
        />
      </div>
    </template>
  </div>
</template>
