<script setup lang="ts">
import type { TranslationResponse, TranslationStatus, TranslationSourceType } from '~/types/translation'

const props = defineProps<{
  orgId: number
}>()

const { listTranslations, updateStatus, publishTranslation, getDashboard } = useTranslationApi()

// フィルタ
const filterStatus = ref<string>('')
const filterLanguage = ref<string>('')
const filterSourceType = ref<string>('')

// ページネーション
const currentPage = ref(0)
const pageSize = 20

// データ
const translations = ref<TranslationResponse[]>([])
const totalRecords = ref(0)
const loading = ref(false)
const dashboardLoading = ref(false)

// ダッシュボード統計
const dashboard = ref<{
  totalCount: number
  byStatus: Record<string, number>
  byLanguage: Record<string, number>
} | null>(null)

const statusOptions = [
  { label: '全て', value: '' },
  { label: '下書き', value: 'DRAFT' },
  { label: 'レビュー中', value: 'IN_REVIEW' },
  { label: '承認済み', value: 'APPROVED' },
  { label: '公開中', value: 'PUBLISHED' },
  { label: '要更新', value: 'STALE' },
  { label: '却下', value: 'REJECTED' },
]

const languageOptions = [
  { label: '全て', value: '' },
  { label: '日本語 (ja)', value: 'ja' },
  { label: '英語 (en)', value: 'en' },
  { label: '中国語 (zh)', value: 'zh' },
  { label: '韓国語 (ko)', value: 'ko' },
]

const sourceTypeOptions = [
  { label: '全て', value: '' },
  { label: 'ブログ', value: 'BLOG_POST' },
  { label: 'ナレッジベース', value: 'KNOWLEDGE_BASE' },
  { label: 'お知らせ', value: 'ANNOUNCEMENT' },
  { label: 'イベント', value: 'EVENT' },
  { label: 'フォーム', value: 'FORM' },
]

const sourceTypeLabels: Record<TranslationSourceType, string> = {
  BLOG_POST: 'ブログ',
  KNOWLEDGE_BASE: 'ナレッジベース',
  ANNOUNCEMENT: 'お知らせ',
  EVENT: 'イベント',
  FORM: 'フォーム',
}

async function fetchTranslations() {
  loading.value = true
  try {
    const res = await listTranslations(props.orgId, {
      status: filterStatus.value || undefined,
      language: filterLanguage.value || undefined,
      sourceType: filterSourceType.value || undefined,
      page: currentPage.value,
      size: pageSize,
    })
    translations.value = res.content
    totalRecords.value = res.totalElements
  } catch {
    // エラーは useApi で処理済み
  } finally {
    loading.value = false
  }
}

async function fetchDashboard() {
  dashboardLoading.value = true
  try {
    dashboard.value = await getDashboard(props.orgId)
  } catch {
    // エラーは useApi で処理済み
  } finally {
    dashboardLoading.value = false
  }
}

function onPageChange(event: { page: number }) {
  currentPage.value = event.page
  fetchTranslations()
}

function onFilterChange() {
  currentPage.value = 0
  fetchTranslations()
}

async function onApprove(row: TranslationResponse) {
  await updateStatus(props.orgId, row.id, 'APPROVED' as TranslationStatus)
  fetchTranslations()
  fetchDashboard()
}

async function onPublish(row: TranslationResponse) {
  await publishTranslation(props.orgId, row.id)
  fetchTranslations()
  fetchDashboard()
}

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

const statusCardItems = computed(() => {
  if (!dashboard.value) return []
  return [
    { label: '下書き', value: dashboard.value.byStatus['DRAFT'] ?? 0, color: 'text-gray-500' },
    { label: 'レビュー中', value: dashboard.value.byStatus['IN_REVIEW'] ?? 0, color: 'text-yellow-500' },
    { label: '承認済み', value: dashboard.value.byStatus['APPROVED'] ?? 0, color: 'text-blue-500' },
    { label: '公開中', value: dashboard.value.byStatus['PUBLISHED'] ?? 0, color: 'text-green-500' },
    { label: '要更新', value: dashboard.value.byStatus['STALE'] ?? 0, color: 'text-orange-500' },
    { label: '却下', value: dashboard.value.byStatus['REJECTED'] ?? 0, color: 'text-red-500' },
  ]
})

watch([filterStatus, filterLanguage, filterSourceType], onFilterChange)

onMounted(() => {
  fetchTranslations()
  fetchDashboard()
})
</script>

<template>
  <div class="space-y-4">
    <!-- ダッシュボード統計 -->
    <div v-if="dashboard" class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-7">
      <Card class="text-center">
        <template #content>
          <div class="text-2xl font-bold text-primary">{{ dashboard.totalCount }}</div>
          <div class="text-sm text-muted-color">総件数</div>
        </template>
      </Card>
      <Card v-for="item in statusCardItems" :key="item.label" class="text-center">
        <template #content>
          <div class="text-2xl font-bold" :class="item.color">{{ item.value }}</div>
          <div class="text-sm text-muted-color">{{ item.label }}</div>
        </template>
      </Card>
    </div>

    <!-- フィルタバー -->
    <div class="flex flex-wrap gap-3">
      <Select
        v-model="filterStatus"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        placeholder="ステータス"
        class="w-40"
      />
      <Select
        v-model="filterLanguage"
        :options="languageOptions"
        option-label="label"
        option-value="value"
        placeholder="言語"
        class="w-40"
      />
      <Select
        v-model="filterSourceType"
        :options="sourceTypeOptions"
        option-label="label"
        option-value="value"
        placeholder="コンテンツ種別"
        class="w-48"
      />
    </div>

    <!-- 一覧テーブル -->
    <DataTable
      :value="translations"
      :loading="loading"
      striped-rows
      class="w-full"
    >
      <template #empty>
        <div class="py-8 text-center text-muted-color">翻訳データがありません</div>
      </template>

      <Column field="sourceType" header="種別" style="width: 130px">
        <template #body="{ data }">
          {{ sourceTypeLabels[data.sourceType as TranslationSourceType] ?? data.sourceType }}
        </template>
      </Column>

      <Column field="sourceTitle" header="タイトル">
        <template #body="{ data }">
          <span class="font-medium">{{ data.sourceTitle }}</span>
        </template>
      </Column>

      <Column field="targetLanguage" header="翻訳言語" style="width: 100px">
        <template #body="{ data }">
          <span class="uppercase">{{ data.targetLanguage }}</span>
        </template>
      </Column>

      <Column field="status" header="ステータス" style="width: 130px">
        <template #body="{ data }">
          <TranslationStatusBadge :status="data.status" />
        </template>
      </Column>

      <Column field="updatedAt" header="更新日" style="width: 110px">
        <template #body="{ data }">
          {{ formatDate(data.updatedAt) }}
        </template>
      </Column>

      <Column header="操作" style="width: 160px">
        <template #body="{ data }">
          <div class="flex gap-2">
            <Button
              v-if="data.status === 'DRAFT' || data.status === 'IN_REVIEW'"
              label="承認"
              size="small"
              severity="info"
              @click="onApprove(data)"
            />
            <Button
              v-if="data.status === 'APPROVED'"
              label="公開"
              size="small"
              severity="success"
              @click="onPublish(data)"
            />
            <Button
              label="詳細"
              size="small"
              severity="secondary"
              outlined
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- ページネーション -->
    <Paginator
      :rows="pageSize"
      :total-records="totalRecords"
      :first="currentPage * pageSize"
      @page="onPageChange"
    />
  </div>
</template>
