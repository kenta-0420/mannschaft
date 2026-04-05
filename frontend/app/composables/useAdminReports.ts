import type {
  ReportResponse,
  ReportStatsResponse,
  InternalNoteResponse,
} from '~/types/admin-report'

interface ResolveForm {
  actionType: string
  note: string
  guidelineSection: string
}

interface EscalateForm {
  reason: string
  guidelineSection: string
}

export function useAdminReports() {
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
  const resolveForm = ref<ResolveForm>({ actionType: 'WARNING', note: '', guidelineSection: '' })
  const showEscalateDialog = ref(false)
  const escalateForm = ref<EscalateForm>({ reason: '', guidelineSection: '' })

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
      totalRecords.value = reportsRes.meta?.totalElements ?? reportsRes.data.length
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
      case 'ESCALATED':
        return 'warn'
      case 'RESOLVED':
        return 'success'
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

  return {
    reports,
    stats,
    loading,
    totalRecords,
    page,
    statusFilter,
    selectedReport,
    showDetailDialog,
    notes,
    newNote,
    showResolveDialog,
    resolveForm,
    showEscalateDialog,
    escalateForm,
    statusOptions,
    openDetail,
    addNote,
    review,
    openResolve,
    resolve,
    dismiss,
    openEscalate,
    escalate,
    reopen,
    hideContent,
    restoreContent,
    statusSeverity,
    onPage,
  }
}
