export function useWorkflowStatus() {
  function statusSeverity(status: string) {
    switch (status) {
      case 'DRAFT':
        return 'secondary'
      case 'SUBMITTED':
        return 'info'
      case 'APPROVED':
        return 'success'
      case 'REJECTED':
        return 'danger'
      case 'WITHDRAWN':
        return 'warn'
      default:
        return 'info'
    }
  }

  function statusLabel(status: string) {
    switch (status) {
      case 'DRAFT':
        return '下書き'
      case 'SUBMITTED':
        return '申請中'
      case 'APPROVED':
        return '承認済'
      case 'REJECTED':
        return '却下'
      case 'WITHDRAWN':
        return '取下げ'
      case 'PENDING':
        return '保留中'
      case 'COMPLETED':
        return '完了'
      default:
        return status
    }
  }

  function decisionLabel(decision: string | null) {
    switch (decision) {
      case 'APPROVED':
        return '承認'
      case 'REJECTED':
        return '却下'
      default:
        return '未対応'
    }
  }

  function formatDateTime(dateStr: string | null): string {
    if (!dateStr) return '—'
    return new Date(dateStr).toLocaleString('ja-JP')
  }

  return { statusSeverity, statusLabel, decisionLabel, formatDateTime }
}
