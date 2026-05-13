import { type APIRequestContext } from '@playwright/test'

/**
 * 実機テスト用: seed データの動的取得ヘルパー
 *
 * seed 実行後の実際の ID を API から取得する。
 * seed-e2e-data.js で INSERT した際のハードコード ID に依存しない設計とする。
 */

/**
 * FC東京U-18 のチーム ID を名前で検索して返す
 */
export async function getE2eTeamId(
  request: APIRequestContext,
  accessToken: string,
): Promise<number | undefined> {
  const resp = await request.get('/api/v1/teams?q=FC東京U-18', {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  if (!resp.ok()) return undefined

  const data = await resp.json()
  // レスポンス形式: { data: Team[] } or Team[]
  const items: Array<{ id: number; name: string }> = Array.isArray(data)
    ? data
    : (data.data ?? [])

  const team = items.find((t) => t.name.includes('FC東京U-18'))
  return team?.id
}

/**
 * 指定チームの PUBLIC な活動記録一覧から最初の 1 件を返す
 */
export async function getPublicActivityResult(
  request: APIRequestContext,
  accessToken: string,
  teamId: number,
): Promise<{ id: number; title: string } | undefined> {
  const resp = await request.get(
    `/api/v1/teams/${teamId}/activity-results?visibility=PUBLIC&size=1`,
    {
      headers: { Authorization: `Bearer ${accessToken}` },
    },
  )
  if (!resp.ok()) return undefined

  const data = await resp.json()
  const items: Array<{ id: number; title: string }> = Array.isArray(data)
    ? data
    : (data.data ?? data.content ?? [])

  return items[0]
}

/**
 * ログインして Bearer トークンを取得する
 *
 * setup ファイルで storageState から取得できない場面（API テスト）向け
 */
export async function fetchBearerToken(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<string | undefined> {
  const resp = await request.post('/api/v1/auth/login', {
    data: { email, password },
  })
  if (!resp.ok()) return undefined

  const data = await resp.json()
  // レスポンス形式: { accessToken: string } or { token: string }
  return data.accessToken ?? data.token
}
