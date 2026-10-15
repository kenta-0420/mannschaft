/**
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *
 * 目的: 「リロード時の access_token Cookie 失効を先回りリフレッシュし、コンソールの 401 ノイズを根絶する」
 *       実装（auth.client プラグインの先回りリフレッシュ）のリグレッションテスト。
 *
 * 背景（修正前の挙動）:
 *   access_token は in-memory + HttpOnly Cookie（Max-Age=900s）で管理される。
 *   リロードで in-memory トークンが消えると Cookie だけが頼りになり、Cookie 失効後の初回リロードでは
 *   起動プラグインのうちアルファベット順先鋒（nav-settings.client）が最初の認証 API を撃って
 *   GET /api/v1/settings/nav 401 を食らっていた（interceptor が /auth/refresh → 自動リトライで 200 復帰）。
 *   機能上は無害だがコンソールに赤が出ていた。
 *
 * 修正後（このテストで検証する挙動）:
 *   auth.client が loadFromStorage 後に「Cookie 失効済み（or 期限不明）」を検知した場合のみ、
 *   認証付き API を撃つ前に先回りで /auth/refresh を実行する。これにより settings/nav は 401 を出さず
 *   最初から 200 で返る。
 *
 * 構成メモ:
 *   開発構成ではブラウザアプリは API オリジン（http://localhost:8080）を直接叩く（NUXT_PUBLIC_API_BASE）。
 *   そのため access_token / refresh_token Cookie は :8080 オリジンに属する。
 *   このテストはログイン・Cookie 操作を :8080 オリジンに対して行い、localStorage 操作はアプリオリジン
 *   （:3000）に対して行う。
 *
 * テストユーザー: e2e-user@test.mannschaft.local / TestPass2026!
 */

import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const TEST_USER = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}

// アプリが叩く API オリジン（開発構成では :8080 直接）。Cookie はこのオリジンに属する。
const API_BASE = process.env.NUXT_PUBLIC_API_BASE || 'http://localhost:8080'

test.describe('AUTH-PROACTIVE-REFRESH: リロード時の先回りトークンリフレッシュ', () => {
  test.setTimeout(120_000)
  // このテストは Cookie を手動制御するため、プロジェクト既定の storageState を使わず
  // 未認証の素のコンテキストから開始する（テスト内で API ログインして認証状態を作る）。
  test.use({ storageState: undefined })

  test('PRR-001: access_token Cookie 失効済みリロードで settings/nav の 401 が出ず、先回り refresh で 200 復帰する', async ({
    page,
    context,
  }) => {
    // 上記 test.use({ storageState: undefined }) により、page/context は未認証の素の状態。
    // ここから API ログインして認証状態を作り、Cookie を手動制御する。
    {
      // --- API ブリッジ（WSL2/カスタムポート CORS 回避）---
      // バックエンド CORS は localhost:3000 / 8080 のみ許可するため、worktree 専用ポート（例 :3300）の
      // ブラウザから :8080 への直接 fetch は CORS でブロックされる。
      // そこで :8080 宛のリクエストを Playwright の APIRequestContext で中継し（context の Cookie を共有）、
      // 応答に Access-Control-Allow-Origin（ブラウザ実 origin）+ Allow-Credentials を付与して返す。
      // これは「テスト環境のオリジン差」を吸収するだけで、アプリ側のリフレッシュ判定ロジックには一切介入しない。
      const pageOrigin = new URL(process.env.BASE_URL ?? 'http://localhost:3300').origin
      await context.route(`${API_BASE}/**`, async (route) => {
        const req = route.request()
        // プリフライトはブラウザ要求の許可ヘッダをそのまま反映して 204 で返す。
        if (req.method() === 'OPTIONS') {
          await route.fulfill({
            status: 204,
            headers: {
              'Access-Control-Allow-Origin': pageOrigin,
              'Access-Control-Allow-Credentials': 'true',
              'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
              'Access-Control-Allow-Headers':
                req.headers()['access-control-request-headers'] ?? 'Content-Type,Authorization',
            },
            body: '',
          })
          return
        }
        // 本リクエストはサーバー側（context.request）で実行し Cookie を確実に同送する。
        // origin/referer/host はブラウザの :3300 を指すため除去する（バックエンドの
        // origin 検証に :8080 由来として通すため。アプリのリフレッシュ判定には無関係）。
        const fwdHeaders: Record<string, string> = {}
        for (const [k, v] of Object.entries(req.headers())) {
          const lk = k.toLowerCase()
          if (lk === 'origin' || lk === 'referer' || lk === 'host') continue
          fwdHeaders[k] = v
        }
        const postData = req.postData()
        const resp = await context.request.fetch(req.url(), {
          method: req.method(),
          headers: fwdHeaders,
          data: postData ?? undefined,
        })
        const bodyBuf = await resp.body()
        const headers: Record<string, string> = {}
        for (const [k, v] of Object.entries(resp.headers())) {
          // CORS ヘッダはこちらで上書きするため除外する。
          if (k.toLowerCase().startsWith('access-control-')) continue
          headers[k] = v
        }
        headers['Access-Control-Allow-Origin'] = pageOrigin
        headers['Access-Control-Allow-Credentials'] = 'true'
        await route.fulfill({ status: resp.status(), headers, body: bodyBuf })
      })
      // 1) 実バックエンド（:8080）へ API ログイン。
      //    page.request は context の Cookie ジャーを共有するため、発行された HttpOnly Cookie が
      //    そのまま context（:8080 オリジン）に入る。
      const loginRes = await page.request.post(`${API_BASE}/api/v1/auth/login`, {
        data: { email: TEST_USER.email, password: TEST_USER.password },
      })
      expect(loginRes.ok(), `API ログイン成功（status=${loginRes.status()}）`).toBeTruthy()

      // プロフィール取得（access_token Cookie は page.request が自動送信する）。
      const meRes = await page.request.get(`${API_BASE}/api/v1/users/me`)
      expect(meRes.ok(), `/users/me 取得成功（status=${meRes.status()}）`).toBeTruthy()
      const me = (await meRes.json()).data as {
        id: number
        email: string
        lastName: string
        firstName: string
        avatarUrl: string | null
        systemRole: string | null
        timezone: string | null
      }

      // アプリオリジン（:3000）の個人ダッシュボードへ遷移し、まず正常な認証済み状態を作る。
      // ここではまだ access_token Cookie が有効なので 401 は出ない。
      // localStorage['currentUser'] を書き込み useAuthStore が認証済みと判定できるようにする。
      await page.goto('/my/', { waitUntil: 'domcontentloaded' })
      await page.evaluate(
        (user) => {
          localStorage.setItem('currentUser', JSON.stringify(user))
        },
        {
          id: me.id,
          email: me.email,
          fullName: `${me.lastName} ${me.firstName}`,
          profileImageUrl: me.avatarUrl,
          systemRole: me.systemRole ?? undefined,
          timezone: me.timezone ?? undefined,
        },
      )
      await waitForHydration(page)

      // 2) 「Cookie 失効＝リロード直後」状態を再現する。
      //    - access_token Cookie だけを削除（refresh_token は残す）。
      //    - localStorage['tokenExpiresAt'] を過去値に設定（auth.client が「失効済み」と判定する）。
      const cookiesBefore = await context.cookies()
      const accessCookie = cookiesBefore.find((c) => c.name === 'access_token')
      const refreshCookie = cookiesBefore.find((c) => c.name === 'refresh_token')
      expect(accessCookie, 'ログイン後に access_token Cookie が存在すること').toBeTruthy()
      expect(refreshCookie, 'ログイン後に refresh_token Cookie が存在すること').toBeTruthy()

      // access_token 以外をすべて再投入することで access_token のみを実質削除する。
      await context.clearCookies()
      await context.addCookies(cookiesBefore.filter((c) => c.name !== 'access_token'))

      await page.evaluate(() => localStorage.setItem('tokenExpiresAt', '1'))

      const cookiesAfter = await context.cookies()
      expect(
        cookiesAfter.find((c) => c.name === 'access_token'),
        'access_token Cookie が削除済みであること',
      ).toBeUndefined()
      expect(
        cookiesAfter.find((c) => c.name === 'refresh_token'),
        'refresh_token Cookie は残っていること',
      ).toBeTruthy()

      // 3) ここからリロードに伴うレスポンスのみを記録する（先のセットアップ通信は対象外）。
      //    単一のリロード = 「Cookie 失効後にユーザーがリロードした」状況を厳密にモデル化する。
      const navResponses: { status: number }[] = []
      let refreshCalled = false
      page.on('response', (res) => {
        const url = res.url()
        if (url.includes('/api/v1/settings/nav')) {
          navResponses.push({ status: res.status() })
        }
        if (url.includes('/api/v1/auth/refresh')) {
          refreshCalled = true
        }
      })

      await page.reload({ waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      // 起動プラグイン群（auth.client の先回り refresh → nav-settings の loadFromServer 等）が
      // 走り切るのを待つ。スピナーが消えるまで待機する。
      await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
      // ネットワークが落ち着くまで待つ（プラグインの非同期 API 呼び出しを取りこぼさない）。
      await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {})

      // 4) アサーション
      //    a. settings/nav に 401 が 1 件も無い（修正前は先頭の 1 件が 401 だった）。
      const nav401 = navResponses.filter((r) => r.status === 401)
      expect(
        nav401.length,
        `settings/nav の 401 レスポンスが 0 件であること（実際の status 列: ${JSON.stringify(navResponses.map((r) => r.status))}）`,
      ).toBe(0)

      //    b. 先回り refresh が走っている（/auth/refresh が呼ばれた）。
      expect(refreshCalled, '/auth/refresh が呼ばれていること（先回りリフレッシュが走った証跡）').toBe(true)

      //    c. settings/nav が最終的に 200 で返っている。
      expect(navResponses.length, 'settings/nav が少なくとも 1 回呼ばれていること').toBeGreaterThan(0)
      expect(
        navResponses.every((r) => r.status === 200),
        `settings/nav が全て 200 であること（実際の status 列: ${JSON.stringify(navResponses.map((r) => r.status))}）`,
      ).toBe(true)
    }
  })
})
