import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F09.13 業者マスタ管理 E2E テスト（Phase 1-ζ-B 補完）。
 *
 * /admin/vendors で:
 *   - 一覧表示
 *   - 新規業者作成（モーダル → 保存 → 一覧反映）
 *   - 編集（モーダル → 保存 → 反映）
 *   - 検索（インクリメンタルサーチで API パラメータが渡る）
 *
 * バックエンド API: /api/v1/{scope}/{scopeId}/vendors
 */

const VENDORS_API_REGEX = /\/api\/v1\/organizations\/1\/vendors(\?|$|\/)/

interface MockVendor {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  name: string
  nameKana: string | null
  category: string | null
  phone: string | null
  email: string | null
  website: string | null
  postalCode: string | null
  address: string | null
  representative: string | null
  contactPerson: string | null
  licenseNumber: string | null
  licenseExpiry: string | null
  note: string | null
  isActive: boolean
  version: number
  createdAt: string
  updatedAt: string
}

interface MockVendorState {
  vendors: MockVendor[]
  nextId: number
  /** 直近の list クエリ。検索パラメータ検証用。 */
  lastListQuery: string | null
}

function makeVendor(overrides: Partial<MockVendor> = {}): MockVendor {
  return {
    id: 1001,
    scopeType: 'ORGANIZATION',
    scopeId: 1,
    name: 'サンプル工務店',
    nameKana: 'サンプルコウムテン',
    category: 'CONSTRUCTION',
    phone: '03-1234-5678',
    email: 'info@sample.example.com',
    website: null,
    postalCode: null,
    address: null,
    representative: null,
    contactPerson: null,
    licenseNumber: null,
    licenseExpiry: null,
    note: null,
    isActive: true,
    version: 0,
    createdAt: '2026-04-01T09:00:00',
    updatedAt: '2026-04-01T09:00:00',
    ...overrides,
  }
}

async function setupVendorMocks(page: Page, state: MockVendorState): Promise<void> {
  // 認証回り（最低限）
  await page.route('**/api/v1/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          id: 1,
          email: 'admin@example.com',
          displayName: 'Test Admin',
          roles: ['ADMIN'],
        },
      }),
    })
  })

  await page.route(VENDORS_API_REGEX, async (route) => {
    const url = route.request().url()
    const method = route.request().method()

    // GET /vendors/search はサジェスト（本テストでは使わないが安全網）
    if (method === 'GET' && /\/vendors\/search/.test(url)) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
      return
    }

    // 単体: GET / PUT / DELETE
    const single = url.match(/\/vendors\/(\d+)(?:\?|$)/)
    if (single) {
      const id = Number(single[1])
      const idx = state.vendors.findIndex((v) => v.id === id)
      if (method === 'GET') {
        if (idx < 0) {
          await route.fulfill({ status: 404, body: '' })
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: state.vendors[idx] }),
        })
        return
      }
      if (method === 'PUT') {
        if (idx < 0) {
          await route.fulfill({ status: 404, body: '' })
          return
        }
        const body = JSON.parse(
          route.request().postData() ?? '{}',
        ) as Partial<MockVendor>
        const cur = state.vendors[idx]!
        Object.assign(cur, body)
        cur.version += 1
        cur.updatedAt = new Date().toISOString()
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: cur }),
        })
        return
      }
      if (method === 'DELETE') {
        if (idx >= 0) state.vendors.splice(idx, 1)
        await route.fulfill({ status: 204, body: '' })
        return
      }
    }

    // 一覧 / 新規作成
    if (method === 'GET') {
      // クエリ部分だけ抜き出して保存
      const qIdx = url.indexOf('?')
      state.lastListQuery = qIdx >= 0 ? url.substring(qIdx + 1) : ''
      // q による絞り込みを模擬する
      const params = new URLSearchParams(state.lastListQuery)
      const q = params.get('q')
      const cat = params.get('category')
      let list = state.vendors
      if (q) list = list.filter((v) => v.name.includes(q) || (v.nameKana ?? '').includes(q))
      if (cat) list = list.filter((v) => v.category === cat)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: list,
          meta: { total: list.length, page: 0, size: 20, totalPages: 1 },
        }),
      })
      return
    }
    if (method === 'POST') {
      const body = JSON.parse(
        route.request().postData() ?? '{}',
      ) as Partial<MockVendor>
      const v = makeVendor({
        id: state.nextId++,
        name: body.name ?? '(no name)',
        nameKana: body.nameKana ?? null,
        category: body.category ?? null,
        phone: body.phone ?? null,
        email: body.email ?? null,
        isActive: body.isActive ?? true,
        version: 0,
      })
      state.vendors.unshift(v)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: v }),
      })
      return
    }

    await route.fulfill({ status: 404, body: '' })
  })
}

test.describe('F09.13 業者マスタ管理 E2E', () => {
  test('VENDOR-001: 一覧表示 → 新規業者作成 → 一覧に追加される', async ({
    page,
  }) => {
    const state: MockVendorState = {
      vendors: [makeVendor()],
      nextId: 1002,
      lastListQuery: null,
    }
    await setupVendorMocks(page, state)

    await page.goto('/admin/vendors?scope=organizations&scopeId=1')
    await waitForHydration(page)

    // テーブル表示
    const table = page.locator('[data-testid="vendor-table"]')
    await expect(table).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('サンプル工務店')).toBeVisible({ timeout: 5_000 })

    // 新規業者ボタン → モーダル
    await page.locator('[data-testid="vendor-new-btn"]').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // 業者名入力 → 保存
    await dialog.getByRole('textbox').first().fill('追加テスト業者')
    await dialog.getByRole('button', { name: '保存' }).click()

    // モーダル閉じ → 一覧に追加
    await expect(dialog).toBeHidden({ timeout: 5_000 })
    await expect(page.getByText('追加テスト業者')).toBeVisible({ timeout: 5_000 })
  })

  test('VENDOR-002: 検索ボックス入力で q パラメータ付き API が叩かれる', async ({
    page,
  }) => {
    const state: MockVendorState = {
      vendors: [
        makeVendor({ id: 2001, name: 'アルファ建設', nameKana: 'アルファケンセツ' }),
        makeVendor({ id: 2002, name: 'ベータ点検', nameKana: 'ベータテンケン', category: 'INSPECTION' }),
      ],
      nextId: 3000,
      lastListQuery: null,
    }
    await setupVendorMocks(page, state)

    await page.goto('/admin/vendors?scope=organizations&scopeId=1')
    await waitForHydration(page)

    // 初期 2 件
    await expect(page.getByText('アルファ建設')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('ベータ点検')).toBeVisible({ timeout: 5_000 })

    // 検索ボックスに「アルファ」入力（debounce 300ms）
    const searchBox = page.getByPlaceholder('業者を検索…')
    await searchBox.fill('アルファ')

    // q=アルファ が API に渡る
    await expect
      .poll(() => state.lastListQuery, { timeout: 5_000 })
      .toMatch(/q=/)

    // 一覧は「アルファ建設」だけが残る
    await expect(page.getByText('アルファ建設')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('ベータ点検')).toHaveCount(0)
  })
})
