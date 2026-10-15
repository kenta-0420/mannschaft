/**
 * Blob を一時 ObjectURL 経由でブラウザにダウンロードさせる共通ヘルパー。
 *
 * 既存の PDF/Excel ダウンロード作法（URL.createObjectURL → anchor.click →
 * revokeObjectURL）を 1 箇所に集約する。SSR 環境（document 不在）では何もしない。
 *
 * @param blob ダウンロード対象の Blob
 * @param filename 保存ファイル名（拡張子込み）
 */
export function downloadBlob(blob: Blob, filename: string): void {
  if (typeof document === 'undefined') return
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(url)
}
