<script setup lang="ts">
// F10.6 Phase 10-γ-③-b: システムログページ
import type { SystemLogFileResponse, SystemLogType } from '~/types/system-log'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { fetchLogFiles } = useSystemLogApi()

// フィルター状態
const selectedType = ref<SystemLogType | undefined>(undefined)
const selectedDate = ref<Date>(new Date())

// データ状態
const logFiles = ref<SystemLogFileResponse[]>([])
const loading = ref(false)
const errorMessage = ref<string | null>(null)

// ログ種別セレクト選択肢
interface TypeOption {
  label: string
  value: SystemLogType | undefined
}

const typeOptions = computed<TypeOption[]>(() => [
  { label: t('systemAdmin.logs.typeAll'), value: undefined },
  { label: t('systemAdmin.logs.typeSlowQuery'), value: 'slow-query' },
  { label: t('systemAdmin.logs.typeSsrError'), value: 'ssr-error' },
])

function formatDateParam(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

async function loadLogs() {
  loading.value = true
  errorMessage.value = null
  try {
    const dateParam = selectedDate.value ? formatDateParam(selectedDate.value) : undefined
    logFiles.value = await fetchLogFiles(selectedType.value, dateParam)
  } catch (e) {
    console.error(e)
    errorMessage.value = e instanceof Error ? e.message : String(e)
    logFiles.value = []
  } finally {
    loading.value = false
  }
}

function formatSizeKb(bytes: number): string {
  return (bytes / 1024).toFixed(1)
}

function openDownload(url: string) {
  window.open(url, '_blank', 'noopener,noreferrer')
}

function getTypeSeverity(type: SystemLogType): 'warn' | 'danger' {
  if (type === 'slow-query') return 'warn'
  return 'danger'
}

function getTypeLabel(type: SystemLogType): string {
  if (type === 'slow-query') return t('systemAdmin.logs.typeSlowQuery')
  return t('systemAdmin.logs.typeSsrError')
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl space-y-6 p-4">
    <!-- ヘッダー -->
    <header class="flex items-center gap-3">
      <div class="flex h-10 w-10 items-center justify-center rounded-lg bg-gray-100 dark:bg-gray-900/20">
        <i class="pi pi-file-export text-lg text-gray-500" aria-hidden="true" />
      </div>
      <div>
        <span
          class="rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-semibold text-gray-600 dark:bg-gray-900/30 dark:text-gray-400"
        >
          SYSTEM ADMIN
        </span>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ t('systemAdmin.logs.title') }}
        </h1>
      </div>
    </header>

    <!-- フィルターエリア -->
    <Card>
      <template #content>
        <div class="flex flex-col gap-4 sm:flex-row sm:items-end">
          <!-- ログ種別セレクト -->
          <div class="flex flex-col gap-1.5">
            <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
              {{ t('systemAdmin.logs.columns.type') }}
            </label>
            <Select
              v-model="selectedType"
              :options="typeOptions"
              option-label="label"
              option-value="value"
              class="w-48"
            />
          </div>

          <!-- 日付ピッカー -->
          <div class="flex flex-col gap-1.5">
            <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
              {{ t('systemAdmin.logs.dateLabel') }}
            </label>
            <DatePicker
              v-model="selectedDate"
              date-format="yy-mm-dd"
              show-icon
              class="w-48"
            />
          </div>

          <!-- 取得ボタン -->
          <Button
            :label="t('systemAdmin.logs.fetchButton')"
            icon="pi pi-search"
            :loading="loading"
            @click="loadLogs"
          />
        </div>
      </template>
    </Card>

    <!-- ローディング -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <i class="pi pi-spin pi-spinner mr-2 text-2xl text-surface-400" aria-hidden="true" />
    </div>

    <!-- エラー -->
    <Message v-else-if="errorMessage" severity="error" :closable="false">
      {{ errorMessage }}
    </Message>

    <!-- ファイル一覧テーブル -->
    <template v-else-if="logFiles.length > 0">
      <DataTable
        :value="logFiles"
        striped-rows
        class="text-sm"
      >
        <!-- 種別 -->
        <Column
          field="type"
          :header="t('systemAdmin.logs.columns.type')"
          style="width: 10rem"
        >
          <template #body="{ data: row }: { data: SystemLogFileResponse }">
            <Tag
              :value="getTypeLabel(row.type)"
              :severity="getTypeSeverity(row.type)"
            />
          </template>
        </Column>

        <!-- 日付 -->
        <Column
          field="date"
          :header="t('systemAdmin.logs.columns.date')"
          style="width: 9rem"
        />

        <!-- ファイル名 -->
        <Column
          field="fileName"
          :header="t('systemAdmin.logs.columns.fileName')"
        />

        <!-- サイズ(KB) -->
        <Column
          :header="t('systemAdmin.logs.columns.sizeKb')"
          style="width: 9rem"
        >
          <template #body="{ data: row }: { data: SystemLogFileResponse }">
            {{ formatSizeKb(row.sizeBytes) }}
          </template>
        </Column>

        <!-- ダウンロード -->
        <Column
          :header="t('systemAdmin.logs.columns.download')"
          style="width: 9rem"
        >
          <template #body="{ data: row }: { data: SystemLogFileResponse }">
            <Button
              :label="t('systemAdmin.logs.downloadButton')"
              icon="pi pi-download"
              size="small"
              text
              @click="openDownload(row.downloadUrl)"
            />
          </template>
        </Column>
      </DataTable>
    </template>

    <!-- 空状態 -->
    <div
      v-else-if="!loading && logFiles.length === 0 && errorMessage === null"
      class="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-surface-300 py-16 text-surface-400 dark:border-surface-600"
    >
      <i class="pi pi-inbox text-4xl" aria-hidden="true" />
      <p class="text-sm">{{ t('systemAdmin.logs.empty') }}</p>
    </div>
  </div>
</template>
