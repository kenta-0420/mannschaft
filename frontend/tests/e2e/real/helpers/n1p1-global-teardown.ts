import { apiAs, readAssets, writeAssets, type AssetRecord } from './n1p1-world'

/**
 * CMP-261103-1225 Phase1 実機E2E の後始末。記録した前提データ（時間割・チャンネル・チーム・組織）を記録の逆順で削除する。
 * 中断などで前回の実行が残した記録も、ここで一緒に拾う。
 * 204 / 404（既に無い）以外は記録を残したまま失敗させる（後始末の失敗を隠さない）。
 */
export default async function globalTeardown(): Promise<void> {
  // spec の手直し中に前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1（最終実行では使わない）
  if (process.env.N1P1_KEEP_WORLD === '1') return
  const records = readAssets()
  const remaining: AssetRecord[] = []
  for (const record of [...records].reverse()) {
    const result = await apiAs(record.owner, 'DELETE', record.path)
    console.log(`[n1p1] cleanup ${record.label} -> ${result.status}`)
    if (result.status !== 204 && result.status !== 404) remaining.unshift(record)
  }
  writeAssets(remaining)
  if (remaining.length) throw new Error(`後始末に失敗した前提データが ${remaining.length} 件残っている`)
}
