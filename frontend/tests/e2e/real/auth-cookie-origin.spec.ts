/**
 * このテストはAPIモックを使わない実機テストです（page.route によるAPI横取り禁止）。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *
 * 目的: 「開発時に http://127.0.0.1:3000 でアプリを開くとログイン後15分で強制ログアウトされる」
 *       不具合の再発防止テスト。
 *
 * 根本原因（2026-08-04 実測で確定）:
 *   BE は認証 Cookie（access_token / refresh_token）を SameSite=Strict で発行する
 *   （backend/src/main/java/com/mannschaft/app/auth/controller/AuthLoginController.java）。
 *   SameSite の同一サイト判定はホスト名の文字列で行われるため、127.0.0.1 と localhost は
 *   ブラウザ上「別サイト」扱いになる。したがって http://127.0.0.1:3000 でページを開くと、
 *   http://localhost:8080 からの Set-Cookie がブラウザに保存されない。
 *   ログイン直後はレスポンス body のトークンが in-memory に載るため一見正常に動くが、
 *   15分弱で先回りリフレッシュ（useApi.ts の armProactiveRefresh）が発火した際、
 *   refresh は Cookie だけが頼りのため 401（AUTH_007）→ ログアウトに落ちる。
 *
 *   このテストは必ず http://localhost:3000（127.0.0.1 ではない）から実行し、
 *   ログイン直後に access_token / refresh_token の両方が Cookie として保存されること、
 *   および refresh が 200 で通ることを実機で確認する。もし将来また 127.0.0.1 運用に
 *   引き戻す変更が入った場合、このテストが Cookie 未保存を検知して落ちる。
 *
 * テストユーザー: process.env.TEST_USER_EMAIL ?? e2e-user@test.mannschaft.local
 */

import { test, expect } from '@playwright/test'

const TEST_USER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}

const API_BASE = process.env.NUXT_PUBLIC_API_BASE || 'http://localhost:8080'

test.describe('AUTH-COOKIE-ORIGIN: localhost 起点でのログインで認証 Cookie が保存されるか', () => {
  test.setTimeout(60_000)
  // 素の未認証コンテキストから開始する（プロジェクト既定の storageState を使わない）。
  test.use({ storageState: undefined })

  test('ACO-001: localhost:3000 からのログインで access_token / refresh_token Cookie が両方保存され、refresh も 200 で通る', async ({
    page,
    context,
  }) => {
    // 冒頭で確実に Cookie を空にする（他テストの残留状態を排除）。
    await context.clearCookies()

    // 1) http://localhost:3000/login をブラウザで開く（127.0.0.1 は使わない）。
    await page.goto('/login', { waitUntil: 'domcontentloaded' })
    expect(new URL(page.url()).hostname, 'ページの origin が localhost であること').toBe('localhost')

    // 2) ページの中から fetch でログインする（page.request ではなくブラウザの fetch を使うことで
    //    ページ origin に紐づく本物の Cookie 保存挙動を踏む）。
    const loginResult = await page.evaluate(
      async ({ apiBase, email, password }) => {
        const res = await fetch(`${apiBase}/api/v1/auth/login`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email, password }),
        })
        return { status: res.status, ok: res.ok }
      },
      { apiBase: API_BASE, email: TEST_USER.email, password: TEST_USER.password },
    )
    expect(loginResult.status, `ログインが 200 で返ること（実際: ${loginResult.status}）`).toBe(200)

    // 3) 保存された Cookie に access_token / refresh_token が両方存在すること。
    const cookies = await context.cookies()
    const cookieNames = cookies.map((c) => c.name)
    const accessCookie = cookies.find((c) => c.name === 'access_token')
    const refreshCookie = cookies.find((c) => c.name === 'refresh_token')
    expect(
      accessCookie,
      `access_token Cookie が保存されていること（保存済み Cookie 一覧: ${JSON.stringify(cookieNames)}）`,
    ).toBeTruthy()
    expect(
      refreshCookie,
      `refresh_token Cookie が保存されていること（保存済み Cookie 一覧: ${JSON.stringify(cookieNames)}）`,
    ).toBeTruthy()

    // 4) 続けてページ内 fetch で /api/v1/auth/refresh が 200 で通ること
    //    （127.0.0.1 運用ではここが Cookie 未送信により 401 になっていた）。
    const refreshResult = await page.evaluate(async ({ apiBase }) => {
      const res = await fetch(`${apiBase}/api/v1/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
      })
      return { status: res.status, ok: res.ok }
    }, { apiBase: API_BASE })
    expect(
      refreshResult.status,
      `/auth/refresh が 200 で返ること（実際: ${refreshResult.status}。保存済み Cookie 一覧: ${JSON.stringify(cookieNames)}）`,
    ).toBe(200)
  })
})
