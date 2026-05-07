<script setup lang="ts">
import type {
  ErrorReportDetail,
  ErrorReportSeverity,
  ErrorReportStatus,
} from '~/types/error-report'
import type { PageMeta } from '~/types/api'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { list } = useErrorReportAdmin()
const { error: showError } = useNotification()
const router = useRouter()

interface FilterState {
  status: ErrorReportStatus | undefined
  severity: ErrorReportSeverity | undefined
  keyword: string
  from: string
  to: string
}

const filters = ref<FilterState>({
  status: undefined,
  severity: undefined,
  keyword: '',
  from: '',
  to: '',
})

const reports = ref<ErrorReportDetail[]>([])
const meta = ref<PageMeta | null>(null)
const loading = ref(false)
const activeTab = ref('list')

const currentPage = ref(0)
const pageSize = ref(20)

async function load() {
  loading.value = true
  try {
    const res = await list({
      status: filters.value.status,
      severity: filters.value.severity,
      from: filters.value.from || undefined,
      to: filters.value.to || undefined,
      page: currentPage.value,
      size: pageSize.value,
      sort: 'lastOccurredAt,desc',
    })
    reports.value = res.data
    meta.value = res.meta
  } catch (e) {
    console.error(e)
    showError(t('error_report.errors.load_failed'))
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  currentPage.value = 0
  void load()
}

function clearFilters() {
  filters.value = {
    status: undefined,
    severity: undefined,
    keyword: '',
    from: '',
    to: '',
  }
  currentPage.value = 0
  void load()
}

function onPageChange(page: number, size: number) {
  currentPage.value = page
  pageSize.value = size
  void load()
}

function openDetail(id: number) {
  void router.push(`/system-admin/error-reports/${id}`)
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="container mx-auto max-w-7xl space-y-4 p-4">
    <header class="flex items-center justify-between">
      <h1 class="flex items-center gap-2 text-xl font-bold">
        <i class="pi pi-exclamation-triangle" aria-hidden="true" />
        {{ t('error_report.title') }}
      </h1>
    </header>

    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab value="list">{{ t('error_report.tabs.list') }}</Tab>
        <Tab value="kanban">{{ t('error_report.tabs.kanban') }}</Tab>
        <Tab value="stats">{{ t('error_report.tabs.stats') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="list">
          <div class="space-y-4">
            <ErrorReportFilterBar
              v-model="filters"
              @apply="applyFilters"
              @clear="clearFilters"
            />
            <ErrorReportListTable
              :reports="reports"
              :loading="loading"
              :meta="meta"
              @open="openDetail"
              @page-change="onPageChange"
            />
          </div>
        </TabPanel>
        <TabPanel value="kanban">
          <div class="rounded-xl border border-dashed border-surface-300 p-8 text-center text-sm text-surface-500 dark:border-surface-600">
            {{ t('error_report.tabs.coming_soon') }}
          </div>
        </TabPanel>
        <TabPanel value="stats">
          <div class="rounded-xl border border-dashed border-surface-300 p-8 text-center text-sm text-surface-500 dark:border-surface-600">
            {{ t('error_report.tabs.coming_soon') }}
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>
  </div>
</template>
