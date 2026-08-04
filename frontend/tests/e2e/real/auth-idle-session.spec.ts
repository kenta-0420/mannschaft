/**
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *
 * 目的: 「画面を開きっぱなしにしていたら 15 分でログイン画面に戻された」というマスター報告の再現確認。
 *
 * 仕様上の期待:
 *   access_token の寿命は 900 秒（15 分）だが、refresh_token は 7 日〜40 日ある。
 *   FE は失効 60 秒前に先回りリフレッシュを発火し（useApi.ts の armProactiveRefresh）、
 *   成功したら次のタイマーを張り直すため、ユーザーが何も操作しなくてもセッションは継続するはずである。
 *   したがって「15 分放置でログイン画面」は仕様ではなく不具合である。
 *
 * 検証方法:
 *   実 UI からログインし、以後 16 分間いっさい操作せずタブを開いたまま放置する。
 *   タイマーの発火はブラウザの実時刻に依存するため、clock のモックは使わず実時間で待つ
 *   （モックすると Cookie の実失効が起きず、再現性のある証跡にならない）。
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// access_token の寿命（900 秒）を確実に跨ぐ放置時間。
const IDLE_MS = 16 * 60_000

test.describe('AUTH-IDLE: 開きっぱなしのタブでセッションが維持されるか', () => {
  test.setTimeout(IDLE_MS + 180_000)
  // 認証状態は project の storageState（setup-real-user が実 BE ログインで生成）を使う。
  // ログインフォームの駆動は dev サーバーのハイドレーションが不安定で本筋（放置後の
  // セッション維持）と無関係にコケるため、ここでは踏まない。タイマーの武装は
  // auth.client プラグイン起動時の armProactiveRefresh が担うので経路としては等価。

  test('IDLE-001: ログイン後 16 分間無操作でも /login に飛ばされず、先回りリフレッシュでセッションが続く', async ({
    page,
  }) => {
    // --- 観測: refresh 呼び出し・401・/login への遷移をすべて記録する ---
    const refreshCalls: { status: number; atMs: number }[] = []
    const unauthorized: { url: string; atMs: number }[] = []
    const navigations: { url: string; atMs: number }[] = []
    const started = Date.now()

    page.on('response', (res) => {
      const url = res.url()
      if (url.includes('/api/v1/auth/refresh')) {
        refreshCalls.push({ status: res.status(), atMs: Date.now() - started })
      }
      else if (res.status() === 401) {
        unauthorized.push({ url, atMs: Date.now() - started })
      }
    })
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) {
        navigations.push({ url: frame.url(), atMs: Date.now() - started })
      }
    })

    // 1) 認証済み状態で個人ダッシュボードを開く（auth.client が先回りタイマーを武装する）。
    await page.goto('/my/', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    expect(page.url(), 'ログイン直後は /login にいないこと').not.toContain('/login')

    // 2) 16 分間まったく操作しない。タブは開いたまま（reload も click もしない）。
    await page.waitForTimeout(IDLE_MS)

    // 3) 放置後の状態を検証する。
    //    a. ログイン画面に飛ばされていないこと（マスター報告の症状そのもの）。
    expect(
      page.url(),
      `16 分放置後もログイン画面に飛ばされていないこと（遷移履歴: ${JSON.stringify(navigations)}）`,
    ).not.toContain('/login')

    //    b. 先回りリフレッシュが最低 1 回は成功していること。
    const okRefresh = refreshCalls.filter((r) => r.status === 200)
    expect(
      okRefresh.length,
      `/auth/refresh が 200 で成功していること（実際の呼び出し: ${JSON.stringify(refreshCalls)}）`,
    ).toBeGreaterThan(0)

    //    c. 放置後もユーザー操作が通ること＝再遷移して認証必須 API が 200 で返る。
    const afterIdle: { url: string; status: number }[] = []
    page.on('response', (res) => {
      if (res.url().includes('/api/v1/settings/nav')) {
        afterIdle.push({ url: res.url(), status: res.status() })
      }
    })
    await page.goto('/my/', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {})
    expect(page.url(), '放置後の画面遷移でログイン画面に落ちないこと').not.toContain('/login')
    expect(
      afterIdle.some((r) => r.status === 200),
      `放置後も認証必須 API が 200 で返ること（実際: ${JSON.stringify(afterIdle)}）`,
    ).toBe(true)
  })
})
