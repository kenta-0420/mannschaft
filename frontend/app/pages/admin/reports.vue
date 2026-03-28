<script setup lang="ts">
import type {
  ReportResponse,
  ReportStatsResponse,
  InternalNoteResponse,
} from '~/types/admin-report'

definePageMeta({ middleware: 'auth' })

const adminReportApi = useAdminReportApi()
const { success, error: showError } = useNotification()

const reports = ref<ReportResponse[]>([])
const stats = ref<ReportStatsResponse | null>(null)
const loading = ref(true)
const totalRecords = ref(0)
const page = ref(0)
const statusFilter = ref<string | undefined>(undefined)
const selectedReport = ref<ReportResponse | null>(null)
const showDetailDialog = ref(false)
const notes = ref<InternalNoteResponse[]>([])
const newNote = ref('')
const showResolveDialog = ref(false)
const resolveForm = ref({ actionType: 'WARNING', note: '', guidelineSection: '' })
const showEscalateDialog = ref(false)
const escalateForm = ref({ reason: '', guidelineSection: '' })
// const selectedIds = ref<number[]>([])

const statusOptions = [
  { label: 'すべて', value: undefined },
  { label: '未対応', value: 'PENDING' },
  { label: '対応中', value: 'REVIEWING' },
  { label: 'エスカレーション', value: 'ESCALATED' },
  { label: '解決済み', value: 'RESOLVED' },
  { label: '却下', value: 'DISMISSED' },
]

async function load() {
  loading.value = true
  try {
    const [reportsRes, statsRes] = await Promise.all([
      adminReportApi.getReports({ page: page.value, size: 20, status: statusFilter.value }),
      adminReportApi.getReportStats(),
    ])
    reports.value = reportsRes.data
    totalRecords.value = reportsRes.meta?.total ?? reportsRes.data.length
    stats.value = statsRes.data
  } catch {
    showError('レポートの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function openDetail(report: ReportResponse) {
  selectedReport.value = report
  showDetailDialog.value = true
  try {
    const res = await adminReportApi.getReportNotes(report.id)
    notes.value = res.data
  } catch {
    notes.value = []
  }
}

async function addNote() {
  if (!selectedReport.value || !newNote.value.trim()) return
  try {
    await adminReportApi.createReportNote(selectedReport.value.id, { note: newNote.value })
    success('メモを追加しました')
    newNote.value = ''
    const res = await adminReportApi.getReportNotes(selectedReport.value.id)
    notes.value = res.data
  } catch {
    showError('メモの追加に失敗しました')
  }
}

async function review(id: number) {
  try {
    await adminReportApi.reviewReport(id)
    success('レビューを開始しました')
    await load()
  } catch {
    showError('レビュー開始に失敗しました')
  }
}

function openResolve(report: ReportResponse) {
  selectedReport.value = report
  resolveForm.value = { actionType: 'WARNING', note: '', guidelineSection: '' }
  showResolveDialog.value = true
}

async function resolve() {
  if (!selectedReport.value) return
  try {
    await adminReportApi.resolveReport(selectedReport.value.id, resolveForm.value)
    success('レポートを解決しました')
    showResolveDialog.value = false
    await load()
  } catch {
    showError('解決に失敗しました')
  }
}

async function dismiss(id: number) {
  try {
    await adminReportApi.dismissReport(id)
    success('レポートを却下しました')
    await load()
  } catch {
    showError('却下に失敗しました')
  }
}

function openEscalate(report: ReportResponse) {
  selectedReport.value = report
  escalateForm.value = { reason: '', guidelineSection: '' }
  showEscalateDialog.value = true
}

async function escalate() {
  if (!selectedReport.value) return
  try {
    await adminReportApi.escalateReport(selectedReport.value.id, escalateForm.value)
    success('エスカレーションしました')
    showEscalateDialog.value = false
    await load()
  } catch {
    showError('エスカレーションに失敗しました')
  }
}

async function reopen(id: number) {
  try {
    await adminReportApi.reopenReport(id)
    success('レポートを再開しました')
    await load()
  } catch {
    showError('再開に失敗しました')
  }
}

async function hideContent(id: number) {
  try {
    await adminReportApi.hideContent(id)
    success('コンテンツを非表示にしました')
  } catch {
    showError('非表示に失敗しました')
  }
}

async function restoreContent(id: number) {
  try {
    await adminReportApi.restoreContent(id)
    success('コンテンツを復元しました')
  } catch {
    showError('復元に失敗しました')
  }
}

function statusSeverity(status: string) {
  switch (status) {
    case 'PENDING':
      return 'danger'
    case 'REVIEWING':
      return 'warn'
    case 'ESCALATED':
      return 'warn'
    case 'RESOLVED':
      return 'success'
    case 'DISMISSED':
      return 'secondary'
    default:
      return 'secondary'
  }
}

function onPage(event: { page: number }) {
  page.value = event.page
  load()
}

watch(statusFilter, () => {
  page.value = 0
  load()
})
onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">レポート管理</h1>
      <Select
        v-model="statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        placeholder="ステータス"
        class="w-48"
      />
    </div>

    <!-- 統計 -->
    <div v-if="stats" class="mb-4 grid grid-cols-3 gap-3 md:grid-cols-6">
      <Card>
        <template #content>
          <p class="text-xs text-surface-500">未対応</p>
          <p class="text-xl font-bold text-red-500">{{ stats.pendingCount }}</p>
        </template>
      </Card>
      <Card>
        <template #content>
          <p class="text-xs text-surface-500">対応中</p>
          <p class="text-xl font-bold text-yellow-500">{{ stats.reviewingCount }}</p>
        </template>
      </Card>
      <Card>
        <template #content>
          <p class="text-xs text-surface-500">エスカレ</p>
          <p class="text-xl font-bold text-orange-500">{{ stats.escalatedCount }}</p>
        </template>
      </Card>
      <Card>
        <template #content>
          <p class="text-xs text-surface-500">解決済</p>
          <p class="text-xl font-bold text-green-500">{{ stats.resolvedCount }}</p>
        </template>
      </Card>
      <Card>
        <template #content>
          <p class="text-xs text-surface-500">却下</p>
          <p class="text-xl font-bold text-surface-400">{{ stats.dismissedCount }}</p>
        </template>
      </Card>
      <Card>
        <template #content>
          <p class="text-xs text-surface-500">合計</p>
          <p class="text-xl font-bold text-primary">{{ stats.totalCount }}</p>
        </template>
      </Card>
    </div>

    <DataTable
      :value="reports"
      :loading="loading"
      :lazy="true"
      :paginator="true"
      :rows="20"
      :total-records="totalRecords"
      :first="page * 20"
      data-key="id"
      striped-rows
      @page="onPage"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">レポートがありません</div>
      </template>
      <Column header="ID" style="width: 60px">
        <template #body="{ data }">
          <span class="text-xs text-surface-500">#{{ data.id }}</span>
        </template>
      </Column>
      <Column header="ステータス" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column field="targetType" header="対象種別" style="width: 100px" />
      <Column field="reason" header="理由" />
      <Column header="報告日" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">{{ new Date(data.createdAt).toLocaleString('ja-JP') }}</span>
        </template>
      </Column>
      <Column header="操作" style="width: 300px">
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Button
              v-if="data.status === 'PENDING'"
              label="レビュー"
              size="small"
              @click="review(data.id)"
            />
            <Button
              v-if="data.status === 'REVIEWING'"
              label="解決"
              size="small"
              severity="success"
              @click="openResolve(data)"
            />
            <Button
              v-if="data.status === 'REVIEWING'"
              label="却下"
              size="small"
              severity="secondary"
              @click="dismiss(data.id)"
            />
            <Button
              v-if="data.status === 'REVIEWING'"
              label="エスカレ"
              size="small"
              severity="warn"
              @click="openEscalate(data)"
            />
            <Button
              v-if="data.status === 'RESOLVED' || data.status === 'DISMISSED'"
              label="再開"
              size="small"
              severity="info"
              @click="reopen(data.id)"
            />
            <Button icon="pi pi-eye" size="small" severity="info" text @click="openDetail(data)" />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 詳細ダイアログ -->
    <Dialog
      v-model:visible="showDetailDialog"
      header="レポート詳細"
      :style="{ width: '700px' }"
      modal
    >
      <div v-if="selectedReport" class="flex flex-col gap-4">
        <div class="grid grid-cols-2 gap-3">
          <div>
            <p class="text-xs text-surface-500">対象種別</p>
            <p>{{ selectedReport.targetType }}</p>
          </div>
          <div>
            <p class="text-xs text-surface-500">ステータス</p>
            <Tag :value="selectedReport.status" :severity="statusSeverity(selectedReport.status)" />
          </div>
          <div>
            <p class="text-xs text-surface-500">理由</p>
            <p>{{ selectedReport.reason }}</p>
          </div>
          <div>
            <p class="text-xs text-surface-500">報告日</p>
            <p class="text-sm">{{ new Date(selectedReport.createdAt).toLocaleString('ja-JP') }}</p>
          </div>
        </div>
        <div v-if="selectedReport.description">
          <p class="text-xs text-surface-500">詳細</p>
          <p class="text-sm">{{ selectedReport.description }}</p>
        </div>
        <div class="flex gap-2">
          <Button
            label="コンテンツ非表示"
            size="small"
            severity="warn"
            @click="hideContent(selectedReport.id)"
          />
          <Button
            label="コンテンツ復元"
            size="small"
            severity="info"
            @click="restoreContent(selectedReport.id)"
          />
        </div>

        <Divider />
        <h3 class="text-sm font-semibold">内部メモ</h3>
        <div class="max-h-40 space-y-2 overflow-y-auto">
          <div v-for="note in notes" :key="note.id" class="rounded border border-surface-200 p-2">
            <p class="text-sm">{{ note.note }}</p>
            <p class="text-xs text-surface-400">
              {{ new Date(note.createdAt).toLocaleString('ja-JP') }}
            </p>
          </div>
          <p v-if="notes.length === 0" class="text-sm text-surface-400">メモがありません</p>
        </div>
        <div class="flex gap-2">
          <InputText v-model="newNote" placeholder="メモを入力..." class="flex-1" />
          <Button label="追加" size="small" @click="addNote" />
        </div>
      </div>
    </Dialog>

    <!-- 解決ダイアログ -->
    <Dialog
      v-model:visible="showResolveDialog"
      header="レポート解決"
      :style="{ width: '500px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">アクション種別</label>
          <Select
            v-model="resolveForm.actionType"
            :options="[
              { label: '警告', value: 'WARNING' },
              { label: 'コンテンツ削除', value: 'CONTENT_DELETE' },
              { label: 'アカウント凍結', value: 'ACCOUNT_FREEZE' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">メモ</label>
          <Textarea v-model="resolveForm.note" rows="3" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">ガイドラインセクション</label>
          <InputText v-model="resolveForm.guidelineSection" class="w-full" />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="showResolveDialog = false" />
          <Button label="解決する" severity="success" @click="resolve" />
        </div>
      </template>
    </Dialog>

    <!-- エスカレーションダイアログ -->
    <Dialog
      v-model:visible="showEscalateDialog"
      header="エスカレーション"
      :style="{ width: '500px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">理由</label>
          <Textarea v-model="escalateForm.reason" rows="3" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">ガイドラインセクション</label>
          <InputText v-model="escalateForm.guidelineSection" class="w-full" />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="showEscalateDialog = false" />
          <Button label="エスカレーションする" severity="warn" @click="escalate" />
        </div>
      </template>
    </Dialog>
  </div>
</template>
