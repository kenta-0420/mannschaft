import { existsSync, readFileSync } from 'node:fs'
import { API, ASSET_FILE, netFetch, type AssetRecord } from './f0121-org-groups-world'

/**
 * F01.2.1 実機E2E の後始末（Playwright の globalTeardown）。
 *
 * spec が作成した組織・チームの記録（`test-results/f0121-assets.jsonl`）を読み、テストの途中で削除済みでないものを
 * すべて削除する。テストが途中で失敗しても実行の最後に必ず走る。チームを先、組織を後に消す。
 * 通信例外（ECONNRESET 等）は共通の netFetch で再試行し（HTTP 応答は再試行しない）、それでも失敗した資産や
 * ログイン失敗・204 以外の応答は失敗一覧に記録して残りの削除を続け、最後に一覧を付けて失敗させる。
 */
export default async function globalTeardown(): Promise<void> {
  if (!existsSync(ASSET_FILE)) return
  const records = readFileSync(ASSET_FILE, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((line) => JSON.parse(line) as AssetRecord)
  const deleted = new Set(records.filter((r) => r.deleted).map((r) => `${r.kind}/${r.slug}`))
  const pending = new Map<string, AssetRecord>()
  for (const r of records) {
    const key = `${r.kind}/${r.slug}`
    if (!r.deleted && !deleted.has(key)) pending.set(key, r)
  }
  const ordered = [...pending.values()].sort((a, b) => (a.kind === b.kind ? 0 : a.kind === 'teams' ? -1 : 1))
  const tokens = new Map<string, string>()
  const failures: string[] = []
  for (const r of ordered) {
    const key = `${r.kind}/${r.slug}`
    try {
      let token = tokens.get(r.owner.email)
      if (!token) {
        const login = await netFetch(`${API}/api/v1/auth/login`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(r.owner),
        })
        if (!login.ok) {
          failures.push(`${key}: 所有者のログイン失敗 ${login.status} ${await login.text()}`)
          continue
        }
        token = (await login.json()).data.accessToken as string
        tokens.set(r.owner.email, token)
      }
      const res = await netFetch(`${API}/api/v1/${key}`, {
        method: 'DELETE',
        headers: { authorization: `Bearer ${token}` },
      })
      if (res.status !== 204) failures.push(`${key}: ${res.status} ${await res.text()}`)
    } catch (error) {
      // 再試行しても通信できなかった資産は記録して、残りの削除を続ける
      const cause = error instanceof Error ? `${error.message}${error.cause ? ` (${String(error.cause)})` : ''}` : String(error)
      failures.push(`${key}: 通信失敗 ${cause}`)
    }
  }
  console.log(`[F0121] globalTeardown: 削除 ${ordered.length - failures.length}/${ordered.length} 件（途中削除済み ${deleted.size} 件）`)
  if (failures.length > 0) throw new Error(`[F0121] 後始末の失敗:\n${failures.join('\n')}`)
}
