/**
 * バイト数を人間可読な文字列に変換する（1024 進・小数1桁）
 * 例: 1536 → "1.5 KB" / 2097152 → "2.0 MB" / 1073741824 → "1.0 GB"
 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`
}
