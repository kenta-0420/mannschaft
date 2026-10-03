/**
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *
 * 目的: 「タブを複数開きっぱなしにしていたらログイン画面に戻された」ケースの再現確認。
 *
 * 背景（疑っている競合）:
 *   refresh_token はサーバー側でローテーションされ、使用済みトークンは失効する。
 *   Cookie はブラウザの全タブで共有されるため、2 つのタブがほぼ同時に先回りリフレッシュを
 *   発火すると、後発のタブが「すでに使用済み（失効済み）の refresh_token」を送ってしまい、
 *   401 → auth_failed → ログアウト（/login?reason=session_expired）に落ちる恐れがある。
 *   先回りリフレッシュの二重発火ガード（refreshPromise）はモジュールスコープ＝タブ単位のため、
 *   タブ間の同時発火は防げない。
 *
 * テストユーザー: setup-real-user が生成した storageState を使用。
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// access_token の寿命（900 秒）を確実に跨ぐ放置時間。
const IDLE_MS = 16 * 60_000

test.describe('AUTH-IDLE-MULTITAB: 複数タブ放置時のセッション維持', () => {
  test.setTimeout(IDLE_MS + 240_000)

  test('IDLE-002: 2 タブを 16 分放置してもどちらもログイン画面に飛ばされない', async ({ context }) => {
    const started = Date.now()
    const refreshCalls: { tab: string; status: number; atMs: number }[] = []
    const navigations: { tab: string; url: string; atMs: number }[] = []

    const openTab = async (name: string) => {
      const p = await context.newPage()
      p.on('response', (res) => {
        if (res.url().includes('/api/v1/auth/refresh')) {
          refreshCalls.push({ tab: name, status: res.status(), atMs: Date.now() - started })
        }
      })
      p.on('framenavigated', (frame) => {
        if (frame === p.mainFrame()) {
          navigations.push({ tab: name, url: frame.url(), atMs: Date.now() - started })
        }
      })
      await p.goto('/my/', { waitUntil: 'domcontentloaded' })
      await waitForHydration(p)
      return p
    }

    // 2 タブをほぼ同時に開く＝先回りリフレッシュのタイマーもほぼ同時刻に武装される。
    const tabA = await openTab('A')
    const tabB = await openTab('B')

    // 16 分間まったく操作しない。
    await tabA.waitForTimeout(IDLE_MS)

    // どちらのタブもログイン画面に落ちていないこと。
    expect(
      tabA.url(),
      `タブ A がログイン画面に飛ばされていないこと（refresh: ${JSON.stringify(refreshCalls)} / 遷移: ${JSON.stringify(navigations)}）`,
    ).not.toContain('/login')
    expect(
      tabB.url(),
      `タブ B がログイン画面に飛ばされていないこと（refresh: ${JSON.stringify(refreshCalls)} / 遷移: ${JSON.stringify(navigations)}）`,
    ).not.toContain('/login')

    // refresh が 401/403 を返していないこと（＝ローテーション競合で失効していない）。
    const failed = refreshCalls.filter((r) => r.status === 401 || r.status === 403)
    expect(
      failed.length,
      `/auth/refresh が認証失敗を返していないこと（実際: ${JSON.stringify(refreshCalls)}）`,
    ).toBe(0)
  })
})
