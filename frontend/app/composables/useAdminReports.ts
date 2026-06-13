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
  const { t } = useI18n()

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

  const statusOptions = computed(() => [
    { label: t('admin_report.status_options.all'), value: undefined },
    { label: t('admin_report.status_options.pending'), value: 'PENDING' },
    { label: t('admin_report.status_options.reviewing'), value: 'REVIEWING' },
    { label: t('admin_report.status_options.escalated'), value: 'ESCALATED' },
    { label: t('admin_report.status_options.resolved'), value: 'RESOLVED' },
    { label: t('admin_report.status_options.dismissed'), value: 'DISMISSED' },
  ])

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
      showError(t('admin_report.messages.load_failed'))
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
      success(t('admin_report.messages.note_added'))
      newNote.value = ''
      const res = await adminReportApi.getReportNotes(selectedReport.value.id)
      notes.value = res.data
    } catch {
      showError(t('admin_report.messages.note_add_failed'))
    }
  }

  async function review(id: number) {
    try {
      await adminReportApi.reviewReport(id)
      success(t('admin_report.messages.review_started'))
      await load()
    } catch {
      showError(t('admin_report.messages.review_failed'))
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
      success(t('admin_report.messages.resolved'))
      showResolveDialog.value = false
      await load()
    } catch {
      showError(t('admin_report.messages.resolve_failed'))
    }
  }

  async function dismiss(id: number) {
    try {
      await adminReportApi.dismissReport(id)
      success(t('admin_report.messages.dismissed'))
      await load()
    } catch {
      showError(t('admin_report.messages.dismiss_failed'))
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
      success(t('admin_report.messages.escalated'))
      showEscalateDialog.value = false
      await load()
    } catch {
      showError(t('admin_report.messages.escalate_failed'))
    }
  }

  async function reopen(id: number) {
    try {
      await adminReportApi.reopenReport(id)
      success(t('admin_report.messages.reopened'))
      await load()
    } catch {
      showError(t('admin_report.messages.reopen_failed'))
    }
  }

  async function hideContent(id: number) {
    try {
      await adminReportApi.hideContent(id)
      success(t('admin_report.messages.content_hidden'))
    } catch {
      showError(t('admin_report.messages.hide_failed'))
    }
  }

  async function restoreContent(id: number) {
    try {
      await adminReportApi.restoreContent(id)
      success(t('admin_report.messages.content_restored'))
    } catch {
      showError(t('admin_report.messages.restore_failed'))
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
