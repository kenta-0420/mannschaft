<script setup lang="ts">
import type { TranslationResponse, TranslationStatus, TranslationSourceType } from '~/types/translation'

const props = defineProps<{
  orgId: number
}>()

const { listTranslations, updateStatus, publishTranslation, getDashboard } = useTranslationApi()
const { t } = useI18n()

const filterStatus = ref<string>('')
const filterLanguage = ref<string>('')
const filterSourceType = ref<string>('')

const currentPage = ref(0)
const pageSize = 20

const translations = ref<TranslationResponse[]>([])
const totalRecords = ref(0)
const loading = ref(false)
const dashboardLoading = ref(false)

const dashboard = ref<{
  totalCount: number
  byStatus: Record<string, number>
  byLanguage: Record<string, number>
} | null>(null)

const statusOptions = computed(() => [
  { label: t('translation.filter_all'), value: '' },
  { label: t('translation.status_draft'), value: 'DRAFT' },
  { label: t('translation.status_in_review'), value: 'IN_REVIEW' },
  { label: t('translation.status_approved'), value: 'APPROVED' },
  { label: t('translation.status_published'), value: 'PUBLISHED' },
  { label: t('translation.status_stale'), value: 'STALE' },
  { label: t('translation.status_rejected'), value: 'REJECTED' },
])

const languageOptions = computed(() => [
  { label: t('translation.filter_all'), value: '' },
  { label: t('translation.language_ja'), value: 'ja' },
  { label: t('translation.language_en'), value: 'en' },
  { label: t('translation.language_zh'), value: 'zh' },
  { label: t('translation.language_ko'), value: 'ko' },
])

const sourceTypeOptions = computed(() => [
  { label: t('translation.filter_all'), value: '' },
  { label: t('translation.source_blog_post'), value: 'BLOG_POST' },
  { label: t('translation.source_knowledge_base'), value: 'KNOWLEDGE_BASE' },
  { label: t('translation.source_announcement'), value: 'ANNOUNCEMENT' },
  { label: t('translation.source_event'), value: 'EVENT' },
  { label: t('translation.source_form'), value: 'FORM' },
])

const sourceTypeLabels = computed((): Record<TranslationSourceType, string> => ({
  BLOG_POST: t('translation.source_blog_post'),
  KNOWLEDGE_BASE: t('translation.source_knowledge_base'),
  ANNOUNCEMENT: t('translation.source_announcement'),
  EVENT: t('translation.source_event'),
  FORM: t('translation.source_form'),
}))

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
    { label: t('translation.status_draft'), value: dashboard.value.byStatus['DRAFT'] ?? 0, color: 'text-gray-500' },
    { label: t('translation.status_in_review'), value: dashboard.value.byStatus['IN_REVIEW'] ?? 0, color: 'text-yellow-500' },
    { label: t('translation.status_approved'), value: dashboard.value.byStatus['APPROVED'] ?? 0, color: 'text-blue-500' },
    { label: t('translation.status_published'), value: dashboard.value.byStatus['PUBLISHED'] ?? 0, color: 'text-green-500' },
    { label: t('translation.status_stale'), value: dashboard.value.byStatus['STALE'] ?? 0, color: 'text-orange-500' },
    { label: t('translation.status_rejected'), value: dashboard.value.byStatus['REJECTED'] ?? 0, color: 'text-red-500' },
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
    <div v-if="dashboard" class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-7">
      <Card class="text-center">
        <template #content>
          <div class="text-2xl font-bold text-primary">{{ dashboard.totalCount }}</div>
          <div class="text-sm text-muted-color">{{ $t('translation.total_count') }}</div>
        </template>
      </Card>
      <Card v-for="item in statusCardItems" :key="item.label" class="text-center">
        <template #content>
          <div class="text-2xl font-bold" :class="item.color">{{ item.value }}</div>
          <div class="text-sm text-muted-color">{{ item.label }}</div>
        </template>
      </Card>
    </div>

    <div class="flex flex-wrap gap-3">
      <Select
        v-model="filterStatus"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        :placeholder="$t('translation.placeholder_status')"
        class="w-40"
      />
      <Select
        v-model="filterLanguage"
        :options="languageOptions"
        option-label="label"
        option-value="value"
        :placeholder="$t('translation.placeholder_language')"
        class="w-40"
      />
      <Select
        v-model="filterSourceType"
        :options="sourceTypeOptions"
        option-label="label"
        option-value="value"
        :placeholder="$t('translation.placeholder_source_type')"
        class="w-48"
      />
    </div>

    <DataTable
      :value="translations"
      :loading="loading"
      striped-rows
      class="w-full"
    >
      <template #empty>
        <div class="py-8 text-center text-muted-color">{{ $t('translation.empty') }}</div>
      </template>

      <Column field="sourceType" :header="$t('translation.column_source_type')" style="width: 130px">
        <template #body="{ data }">
          {{ sourceTypeLabels[data.sourceType as TranslationSourceType] ?? data.sourceType }}
        </template>
      </Column>

      <Column field="sourceTitle" :header="$t('translation.column_title')">
        <template #body="{ data }">
          <span class="font-medium">{{ data.sourceTitle }}</span>
        </template>
      </Column>

      <Column field="targetLanguage" :header="$t('translation.column_language')" style="width: 100px">
        <template #body="{ data }">
          <span class="uppercase">{{ data.targetLanguage }}</span>
        </template>
      </Column>

      <Column field="status" :header="$t('translation.column_status')" style="width: 130px">
        <template #body="{ data }">
          <TranslationStatusBadge :status="data.status" />
        </template>
      </Column>

      <Column field="updatedAt" :header="$t('translation.column_updated_at')" style="width: 110px">
        <template #body="{ data }">
          {{ formatDate(data.updatedAt) }}
        </template>
      </Column>

      <Column :header="$t('translation.column_actions')" style="width: 160px">
        <template #body="{ data }">
          <div class="flex gap-2">
            <Button
              v-if="data.status === 'DRAFT' || data.status === 'IN_REVIEW'"
              :label="$t('translation.button_approve')"
              size="small"
              severity="info"
              @click="onApprove(data)"
            />
            <Button
              v-if="data.status === 'APPROVED'"
              :label="$t('translation.button_publish')"
              size="small"
              severity="success"
              @click="onPublish(data)"
            />
            <Button
              :label="$t('translation.button_detail')"
              size="small"
              severity="secondary"
              outlined
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <Paginator
      :rows="pageSize"
      :total-records="totalRecords"
      :first="currentPage * pageSize"
      @page="onPageChange"
    />
  </div>
</template>
