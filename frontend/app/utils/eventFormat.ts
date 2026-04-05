export function statusSeverity(status: string) {
  switch (status) {
    case 'DRAFT':
      return 'secondary'
    case 'PUBLISHED':
      return 'success'
    case 'CANCELLED':
      return 'danger'
    case 'CLOSED':
      return 'warn'
    default:
      return 'info'
  }
}

export function statusLabel(status: string) {
  switch (status) {
    case 'DRAFT':
      return '下書き'
    case 'PUBLISHED':
      return '公開中'
    case 'CANCELLED':
      return 'キャンセル'
    case 'CLOSED':
      return '終了'
    default:
      return status
  }
}

export function regStatusLabel(status: string) {
  switch (status) {
    case 'PENDING':
      return '保留中'
    case 'APPROVED':
      return '承認済'
    case 'REJECTED':
      return '拒否'
    case 'CANCELLED':
      return 'キャンセル'
    default:
      return status
  }
}

export function regStatusSeverity(status: string) {
  switch (status) {
    case 'PENDING':
      return 'warn'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'CANCELLED':
      return 'secondary'
    default:
      return 'info'
  }
}

export function formatDateTime(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleString('ja-JP')
}
