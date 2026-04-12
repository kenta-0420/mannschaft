// ネイティブ confirm() の代替。PrimeVue useConfirm をラップ
export function useConfirmDialog() {
  const confirm = useConfirm()

  function confirmAction(options: {
    message: string
    header?: string
    onAccept: () => void | Promise<void>
    onReject?: () => void
  }) {
    confirm.require({
      message: options.message,
      header: options.header ?? '確認',
      icon: 'pi pi-exclamation-triangle',
      rejectClass: 'p-button-text',
      acceptClass: 'p-button-danger',
      accept: options.onAccept,
      reject: options.onReject,
    })
  }

  return { confirmAction }
}
