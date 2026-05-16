import type { Page } from '@playwright/test'
import type { LegalFiling } from '../../../app/types/succession'

/**
 * F09.15 区分所有者承継支援 — E2E テスト共通ヘルパー。
 *
 * residence-status/helpers.ts のパターンを踏襲し、以下を提供する:
 * - 認証情報を localStorage に注入する setupAuth
 * - レイアウト共通 API をモックする setupLayoutMocks
 * - 法的手続き準備ページ固有 API をモックする setupLegalFilingsMocks
 * - fixture builder（LegalFiling）
 */

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------

export const DEFAULT_ORG_ID = 1
export const ADMIN_USER = { userId: 1, displayName: 'E2E Admin', role: 'ADMIN' } as const

// ---------------------------------------------------------------------------
// 型定義
// ---------------------------------------------------------------------------

export interface AuthRole {
  userId: number
  displayName: string
  role: 'ADMIN' | 'MEMBER' | 'GUEST'
}

// ---------------------------------------------------------------------------
// 認証注入
// ---------------------------------------------------------------------------

/**
 * localStorage に認証情報を注入する（token refresh をバイパス）。
 * residence-status/helpers.ts の setupAuth と同方式。
 */
export async function setupAuth(page: Page, opts: AuthRole): Promise<void> {
  await page.addInitScript((args) => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: args.userId,
        email: `e2e-${args.role.toLowerCase()}@example.com`,
        displayName: args.displayName,
        profileImageUrl: null,
        role: args.role,
      }),
    )
  }, opts)
}

// ---------------------------------------------------------------------------
// レイアウト共通モック
// ---------------------------------------------------------------------------

/**
 * レイアウト共通 API と認証 API をモックする。
 * auth.middleware.ts が呼び出す /api/v1/auth/me をモックして
 * ログインページへのリダイレクトを防ぐ。
 */
export async function setupLayoutMocks(page: Page, opts: AuthRole): Promise<void> {
  // OPTIONS プリフライトを一括処理
  await page.route('**/api/v1/**', async (route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Authorization',
          'Access-Control-Max-Age': '86400',
        },
      })
      return
    }
    await route.fallback()
  })

  // 認証確認（auth middleware が呼ぶ）
  await page.route('**/api/v1/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          id: opts.userId,
          email: `e2e-${opts.role.toLowerCase()}@example.com`,
          displayName: opts.displayName,
          avatarUrl: null,
        },
      },
    })
  })

  // token refresh
  await page.route('**/api/v1/auth/refresh', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          accessToken: 'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
          refreshToken: 'e2e-refresh-token-placeholder',
        },
      },
    })
  })

  // 未読通知数
  await page.route('**/api/v1/notifications/unread-count', async (route) => {
    await route.fulfill({ status: 200, json: { data: { total: 0 } } })
  })

  // チャットチャンネル
  await page.route('**/api/v1/chat/channels**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: [] } })
    } else {
      await route.fallback()
    }
  })

  // メンション
  await page.route('**/api/v1/mentions**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: [] } })
    } else {
      await route.fallback()
    }
  })

  // ロール・権限確認
  await page.route('**/api/v1/role-accesses**', async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          role: opts.role,
          permissions: opts.role === 'ADMIN' ? ['READ', 'WRITE', 'MANAGE'] : ['READ'],
        },
      },
    })
  })

  // 通知一覧
  await page.route('**/api/v1/notifications**', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { data: [] } })
    } else {
      await route.fallback()
    }
  })

  // 組織情報
  await page.route(`**/api/v1/organizations/${DEFAULT_ORG_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          id: DEFAULT_ORG_ID,
          name: 'E2Eテスト管理組合',
          description: 'E2Eテスト用組織',
        },
      },
    })
  })

  // 組織権限
  await page.route(`**/api/v1/organizations/${DEFAULT_ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        data: {
          roleName: opts.role,
          permissions: opts.role === 'ADMIN' ? ['member.manage', 'READ', 'WRITE'] : ['READ'],
        },
      },
    })
  })
}

// ---------------------------------------------------------------------------
// legal-filings 固有モックセットアップ
// ---------------------------------------------------------------------------

/**
 * 法的手続き準備ページ固有 API をモックする。
 * setupLayoutMocks の後に呼び出すこと。
 *
 * モック対象:
 * - GET  /api/v1/organizations/{orgId}/succession/legal-filings
 * - POST /api/v1/organizations/{orgId}/succession/legal-filings
 * - POST /api/v1/organizations/{orgId}/succession/legal-filings/{id}/evidence-package
 * - GET  /api/v1/organizations/{orgId}/succession/legal-filings/{id}/evidence-package/download-url
 *
 * `filings` を mutable な内部リストとして保持し、一覧 GET は呼び出しごとに最新を返す。
 * これにより「生成ボタン → 一覧再取得後に状態が変わる」シナリオを再現できる。
 */
export async function setupLegalFilingsMocks(
  page: Page,
  orgId: number,
  opts: {
    filings?: LegalFiling[]
    createResponse?: Partial<LegalFiling>
    evidenceUrl?: string
    failOnBuild?: boolean
  } = {},
): Promise<void> {
  // 状態を保持する mutable リスト（一覧 API は最新の filings を返す）
  const state: { filings: LegalFiling[] } = {
    filings: opts.filings ? [...opts.filings] : [],
  }
  const evidenceUrl = opts.evidenceUrl ?? 'https://example.com/signed-url/evidence.zip'
  const failOnBuild = opts.failOnBuild ?? false

  // 一覧 GET
  await page.route(
    `**/api/v1/organizations/${orgId}/succession/legal-filings`,
    async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({ status: 200, json: { data: state.filings } })
        return
      }
      if (method === 'POST') {
        // 起票: createResponse + リクエスト body をマージして新規 filing を作る
        const requestBody = route.request().postDataJSON() as {
          residentRegistryId: number
          dwellingUnitId: number
          filingType: 'ABSENTEE_PROPERTY_MANAGER' | 'INHERITANCE_LIQUIDATOR'
          note?: string
        }
        const newFiling = buildLegalFiling({
          id: `filing-uuid-new-${state.filings.length + 1}`,
          organizationId: orgId,
          residentRegistryId: requestBody.residentRegistryId,
          dwellingUnitId: requestBody.dwellingUnitId,
          filingType: requestBody.filingType,
          note: requestBody.note,
          evidencePackageS3Key: undefined,
          evidenceBuiltAt: undefined,
          ...opts.createResponse,
        })
        state.filings = [...state.filings, newFiling]
        await route.fulfill({ status: 201, json: { data: newFiling } })
        return
      }
      await route.fallback()
    },
  )

  // 証拠 ZIP 生成 POST / ダウンロード URL GET
  // パスが共通プレフィックスを持つため、一覧 URL のあとに登録（後勝ち優先で対象 URL を捕まえる）
  await page.route(
    `**/api/v1/organizations/${orgId}/succession/legal-filings/*/evidence-package`,
    async (route) => {
      if (route.request().method() !== 'POST') {
        await route.fallback()
        return
      }
      if (failOnBuild) {
        await route.fulfill({
          status: 500,
          json: { error: { code: 'INTERNAL_ERROR', message: '証拠 ZIP 生成失敗' } },
        })
        return
      }
      // URL から filing ID を抜き出して該当 filing を更新する
      const url = route.request().url()
      const match = url.match(/legal-filings\/([^/]+)\/evidence-package/)
      const filingId = match?.[1] ?? ''
      const target = state.filings.find((f) => f.id === filingId)
      const updated: LegalFiling = target
        ? {
            ...target,
            evidencePackageS3Key: `s3://mannschaft/evidence/${filingId}.zip`,
            evidenceBuiltAt: '2026-05-15T12:00:00Z',
            evidenceSha256: 'a'.repeat(64),
          }
        : buildLegalFiling({
            id: filingId,
            organizationId: orgId,
            evidencePackageS3Key: `s3://mannschaft/evidence/${filingId}.zip`,
            evidenceBuiltAt: '2026-05-15T12:00:00Z',
          })
      // state を入れ替えて、後続の一覧 GET で反映されるようにする
      state.filings = state.filings.map((f) => (f.id === filingId ? updated : f))
      await route.fulfill({ status: 200, json: { data: updated } })
    },
  )

  await page.route(
    `**/api/v1/organizations/${orgId}/succession/legal-filings/*/evidence-package/download-url`,
    async (route) => {
      if (route.request().method() !== 'GET') {
        await route.fallback()
        return
      }
      await route.fulfill({
        status: 200,
        json: { data: { downloadUrl: evidenceUrl, ttlSeconds: 3600 } },
      })
    },
  )
}

// ---------------------------------------------------------------------------
// fixture builder
// ---------------------------------------------------------------------------

/** LegalFiling の雛形。 */
export function buildLegalFiling(overrides: Partial<LegalFiling> = {}): LegalFiling {
  return {
    id: 'filing-uuid-001',
    organizationId: DEFAULT_ORG_ID,
    dwellingUnitId: 201,
    residentRegistryId: 1001,
    filingType: 'ABSENTEE_PROPERTY_MANAGER',
    templatePdfS3Key: undefined,
    evidencePackageS3Key: undefined,
    evidenceBuiltAt: undefined,
    evidenceSha256: undefined,
    filedExternallyAt: undefined,
    externalCaseNumber: undefined,
    note: undefined,
    createdAt: '2026-05-15T09:00:00Z',
    updatedAt: '2026-05-15T09:00:00Z',
    ...overrides,
  }
}
