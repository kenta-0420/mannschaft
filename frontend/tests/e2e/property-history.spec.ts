import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F09.13 物件履歴台帳 E2E テスト（Phase 1-ζ-B / フロントエンド E2E 部隊）。
 *
 * 既存 E2E パターン（action-memo.spec.ts 等）に倣い、`page.route` で
 * バックエンド API をモックすることで dev サーバ単独で完結させる。
 *
 * カバー範囲:
 * - PH-001: ADMIN ゴールデンパス（一覧 → 新規作成 → 詳細 → ステータス変更 →
 *           編集（VendorPicker）→ PDF 出力 → Excel 出力 → 削除）
 * - PH-002: マスキング検証（MEMBER × MEMBERS_MASKED）
 * - PH-003: ビュー切替（list / timeline / gantt の API 呼び出し検証）
 *
 * NOTE:
 * - `usePropertyWorkPackageApi.exportSingle/exportList` は `useRuntimeConfig().public.apiBase`
 *   をプレフィックスにつけて `$fetch` するため、URL パターンは `**\/api/v1/.../export*` で
 *   モックする（host は config 次第のため glob を活用）。
 * - SplitButton のメインクリックは PDF を呼び出す（PropertyWorkExportButton.vue 既定）。
 *   Excel はメニュー項目から起動する。
 * - ダウンロードは `URL.createObjectURL(blob) + a.click()` 方式のため、
 *   `page.on('download')` で受け取れる。
 */

const SCOPE_PREFIX = '**/api/v1/organizations/1'
const PACKAGES_API_REGEX =
  /\/api\/v1\/organizations\/1\/property-history(\?|$|\/)/
const VENDORS_API = `${SCOPE_PREFIX}/vendors**`

/** 1 件目のパッケージ ID（モック共通）。 */
const PKG_ID = 4001
/** 業者 ID。 */
const VENDOR_ID = 7001

interface MockPackage {
  id: number
  workType: string
  category: string | null
  title: string
  description: string | null
  vendorId: number | null
  vendorNameSnapshot: string | null
  estimatedAmount: number | null
  contractAmount: number | null
  actualAmount: number | null
  currency: string
  plannedStartDate: string | null
  plannedEndDate: string | null
  actualStartDate: string | null
  actualEndDate: string | null
  warrantyUntil: string | null
  isDisclosable: boolean
  visibility: string
  status: string
  attachmentCount: number
  commentCount: number
  tags: string[] | null
  documents: unknown[]
  scopeType: string
  scopeId: number
  dwellingUnitId: number | null
  incidentId: number | null
  incidentDate: string | null
  incidentNarrative: string | null
  budgetTransactionId: number | null
  timelinePostId: number | null
  createdBy: number
  updatedBy: number | null
  createdAt: string
  updatedAt: string
  version: number
  permissions: { canEdit: boolean; canDelete: boolean; canViewAmount: boolean }
}

interface MockState {
  packages: MockPackage[]
  vendors: Array<{
    id: number
    name: string
    nameKana: string | null
    category: string | null
    isActive: boolean
  }>
  /** ADMIN/MEMBER の切替で permissions を変える。 */
  asMember: boolean
  /** 各エンドポイントの呼び出し回数（ビュー切替検証で使う）。 */
  callCounts: { list: number; timeline: number; gantt: number }
  nextId: number
}

function makePackage(overrides: Partial<MockPackage> = {}): MockPackage {
  return {
    id: PKG_ID,
    scopeType: 'ORGANIZATION',
    scopeId: 1,
    dwellingUnitId: null,
    workType: 'RENOVATION',
    category: 'エントランス',
    title: '初期データ：エントランス改修',
    description: null,
    incidentId: null,
    incidentDate: null,
    incidentNarrative: null,
    plannedStartDate: '2026-04-01',
    plannedEndDate: '2026-04-30',
    actualStartDate: null,
    actualEndDate: null,
    vendorId: VENDOR_ID,
    vendorNameSnapshot: '株式会社サンプル工務店',
    estimatedAmount: 1_000_000,
    contractAmount: 1_100_000,
    actualAmount: 1_080_000,
    currency: 'JPY',
    budgetTransactionId: null,
    timelinePostId: null,
    warrantyUntil: '2027-04-30',
    isDisclosable: false,
    visibility: 'MEMBERS_MASKED',
    status: 'PLANNED',
    attachmentCount: 0,
    commentCount: 0,
    tags: null,
    documents: [],
    createdBy: 1,
    updatedBy: null,
    createdAt: '2026-04-01T09:00:00',
    updatedAt: '2026-04-01T09:00:00',
    version: 0,
    permissions: { canEdit: true, canDelete: true, canViewAmount: true },
    ...overrides,
  }
}

function toSummary(p: MockPackage) {
  return {
    id: p.id,
    workType: p.workType,
    category: p.category,
    title: p.title,
    actualEndDate: p.actualEndDate,
    plannedStartDate: p.plannedStartDate,
    plannedEndDate: p.plannedEndDate,
    vendorId: p.vendorId,
    vendorNameSnapshot: p.vendorNameSnapshot,
    actualAmount: p.permissions.canViewAmount ? p.actualAmount : null,
    status: p.status,
    canViewAmount: p.permissions.canViewAmount,
  }
}

/** バイナリ Blob を返すモックレスポンス（PDF / xlsx 共用 — 中身はダミーバイト列）。 */
const FAKE_PDF_BYTES = Buffer.from('%PDF-1.4\n%mock\n', 'utf-8')
const FAKE_XLSX_BYTES = Buffer.from('PK\x03\x04mock-xlsx', 'binary')

async function setupMocks(page: Page, state: MockState): Promise<void> {
  // PR #1000 以降 isAuthenticated = !!state.user。user.json が空のため addInitScript で注入する。
  await page.addInitScript(() => {
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 1,
        email: 'admin@example.com',
        fullName: 'Test Admin',
        profileImageUrl: null,
        systemRole: 'SYSTEM_ADMIN',
      }),
    )
  })

  // 認証関連のモック（storageState に依存しないテスト環境向け）
  await page.route('**/api/v1/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          id: 1,
          email: 'admin@example.com',
          displayName: 'Test Admin',
          roles: state.asMember ? ['MEMBER'] : ['ADMIN'],
        },
      }),
    })
  })

  // === 業者 API モック ===
  await page.route(VENDORS_API, async (route) => {
    const url = route.request().url()
    const method = route.request().method()

    if (method === 'GET' && /\/vendors\/search\?/.test(url)) {
      // サジェスト
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: state.vendors
            .filter((v) => v.isActive)
            .map((v) => ({
              id: v.id,
              name: v.name,
              nameKana: v.nameKana,
              category: v.category,
            })),
        }),
      })
      return
    }

    if (method === 'GET' && /\/vendors\/\d+(\?|$)/.test(url)) {
      // 単体取得
      const m = url.match(/\/vendors\/(\d+)/)
      const id = m ? Number(m[1]) : 0
      const v = state.vendors.find((x) => x.id === id)
      if (!v) {
        await route.fulfill({ status: 404, body: '' })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: v.id,
            scopeType: 'ORGANIZATION',
            scopeId: 1,
            name: v.name,
            nameKana: v.nameKana,
            category: v.category,
            phone: null,
            email: null,
            website: null,
            postalCode: null,
            address: null,
            representative: null,
            contactPerson: null,
            licenseNumber: null,
            licenseExpiry: null,
            note: null,
            isActive: v.isActive,
            version: 0,
            createdAt: '2026-04-01T09:00:00',
            updatedAt: '2026-04-01T09:00:00',
          },
        }),
      })
      return
    }

    if (method === 'GET') {
      // 一覧
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: state.vendors.map((v) => ({
            id: v.id,
            scopeType: 'ORGANIZATION',
            scopeId: 1,
            name: v.name,
            nameKana: v.nameKana,
            category: v.category,
            phone: null,
            email: null,
            website: null,
            postalCode: null,
            address: null,
            representative: null,
            contactPerson: null,
            licenseNumber: null,
            licenseExpiry: null,
            note: null,
            isActive: v.isActive,
            version: 0,
            createdAt: '2026-04-01T09:00:00',
            updatedAt: '2026-04-01T09:00:00',
          })),
          meta: {
            total: state.vendors.length,
            page: 0,
            size: 20,
            totalPages: 1,
          },
        }),
      })
      return
    }

    await route.fulfill({ status: 404, body: '' })
  })

  // === 物件履歴 API モック ===
  await page.route(PACKAGES_API_REGEX, async (route: Route) => {
    const url = route.request().url()
    const method = route.request().method()

    // export
    if (/\/property-history(\/\d+)?\/export/.test(url)) {
      const isExcel = /format=xlsx/.test(url)
      await route.fulfill({
        status: 200,
        contentType: isExcel
          ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
          : 'application/pdf',
        headers: {
          'Content-Disposition': `attachment; filename="property-history.${isExcel ? 'xlsx' : 'pdf'}"`,
        },
        body: isExcel ? FAKE_XLSX_BYTES : FAKE_PDF_BYTES,
      })
      return
    }

    // gantt
    if (/\/property-history\/gantt(\?|$)/.test(url)) {
      state.callCounts.gantt += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: state.packages.map(toSummary) }),
      })
      return
    }

    // timeline
    if (/\/property-history\/timeline(\?|$)/.test(url)) {
      state.callCounts.timeline += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: state.packages.map(toSummary) }),
      })
      return
    }

    // 単体 status 変更
    if (method === 'PATCH' && /\/property-history\/\d+\/status(\?|$)/.test(url)) {
      const m = url.match(/\/property-history\/(\d+)\/status/)
      const id = m ? Number(m[1]) : 0
      const body = JSON.parse(route.request().postData() ?? '{}') as {
        status?: string
      }
      const idx = state.packages.findIndex((p) => p.id === id)
      if (idx < 0) {
        await route.fulfill({ status: 404, body: '' })
        return
      }
      const pkg = state.packages[idx]!
      pkg.status = body.status ?? pkg.status
      pkg.version += 1
      pkg.updatedAt = new Date().toISOString()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: pkg }),
      })
      return
    }

    // 単体取得 / 更新 / 削除
    const single = url.match(/\/property-history\/(\d+)(?:\?|$)/)
    if (single) {
      const id = Number(single[1])
      const idx = state.packages.findIndex((p) => p.id === id)
      if (method === 'GET') {
        if (idx < 0) {
          await route.fulfill({ status: 404, body: '' })
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: state.packages[idx] }),
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
        ) as Partial<MockPackage>
        const cur = state.packages[idx]!
        if (body.vendorId !== undefined) {
          const v = state.vendors.find((x) => x.id === body.vendorId)
          cur.vendorId = body.vendorId
          cur.vendorNameSnapshot = v?.name ?? cur.vendorNameSnapshot
        }
        Object.assign(cur, {
          ...body,
          // overwrite された permissions/version は使わない（楽観ロック簡略化）
          permissions: cur.permissions,
        })
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
        if (idx >= 0) state.packages.splice(idx, 1)
        await route.fulfill({ status: 204, body: '' })
        return
      }
    }

    // 一覧 (GET) / 新規作成 (POST)
    if (method === 'GET') {
      state.callCounts.list += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: state.packages.map(toSummary),
          meta: {
            total: state.packages.length,
            page: 0,
            size: 20,
            totalPages: 1,
          },
        }),
      })
      return
    }
    if (method === 'POST') {
      const body = JSON.parse(
        route.request().postData() ?? '{}',
      ) as Partial<MockPackage>
      const newPkg = makePackage({
        id: state.nextId++,
        workType: body.workType ?? 'RENOVATION',
        title: body.title ?? '(no title)',
        category: body.category ?? null,
        description: body.description ?? null,
        visibility: body.visibility ?? 'MEMBERS_MASKED',
        isDisclosable: body.isDisclosable ?? false,
        status: 'PLANNED',
        vendorId: null,
        vendorNameSnapshot: null,
        estimatedAmount: null,
        contractAmount: null,
        actualAmount: null,
        version: 0,
      })
      state.packages.unshift(newPkg)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: newPkg }),
      })
      return
    }

    await route.fulfill({ status: 404, body: '' })
  })
}

function defaultState(): MockState {
  return {
    packages: [makePackage()],
    vendors: [
      {
        id: VENDOR_ID,
        name: '株式会社サンプル工務店',
        nameKana: 'カブシキガイシャサンプルコウムテン',
        category: 'CONSTRUCTION',
        isActive: true,
      },
      {
        id: VENDOR_ID + 1,
        name: 'テスト点検株式会社',
        nameKana: 'テストテンケンカブシキガイシャ',
        category: 'INSPECTION',
        isActive: true,
      },
    ],
    asMember: false,
    callCounts: { list: 0, timeline: 0, gantt: 0 },
    nextId: PKG_ID + 1,
  }
}

test.describe('F09.13 物件履歴台帳 E2E', () => {
  test('PH-001: ADMIN ゴールデンパス — 作成 → 詳細 → ステータス変更 → 編集 → PDF 出力 → Excel 出力 → 削除', async ({
    page,
  }) => {
    const state = defaultState()
    // 開幕は空状態から始めて、新規作成 → 一覧反映を確認できるようにする
    state.packages = []
    await setupMocks(page, state)

    // === 1. 一覧画面表示 ===
    await page.goto('/property-history?scope=organizations&scopeId=1')
    await waitForHydration(page)

    await expect(
      page.getByRole('button', { name: /新規工事を追加/ }),
    ).toBeVisible({ timeout: 10_000 })
    // 空状態メッセージ
    await expect(page.getByText('履歴がまだ登録されていません')).toBeVisible({
      timeout: 5_000,
    })

    // === 2. 「新規工事を追加」ボタン → モーダル表示 ===
    await page.locator('[data-testid="property-new-package-btn"]').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // === 3. 必須項目入力 → 保存 → モーダル閉じ → 一覧にカード追加 ===
    // タイトル入力（モーダル内 InputText を name で特定できないため最初の textbox を使う）
    await dialog.getByRole('textbox').first().fill('アプローチ階段補修')
    // workType は初期 RENOVATION なのでそのまま
    await dialog.getByRole('button', { name: '保存' }).click()

    // モーダルが閉じる
    await expect(dialog).toBeHidden({ timeout: 5_000 })
    // 一覧に新規カード（モックは unshift で先頭追加）
    await expect(page.getByText('アプローチ階段補修')).toBeVisible({
      timeout: 5_000,
    })

    // === 4. 作成したカードクリック → 詳細ページ遷移 ===
    const newPkgId = PKG_ID + 1
    await page.locator(`[data-testid="property-package-card-${newPkgId}"]`).click()
    await page.waitForURL(`**/property-history/${newPkgId}**`, { timeout: 10_000 })
    await waitForHydration(page)

    // 詳細ページタイトル
    await expect(
      page.getByRole('heading', { name: 'アプローチ階段補修' }),
    ).toBeVisible({ timeout: 10_000 })

    // === 5. ステータス Dropdown を IN_PROGRESS に変更 → トースト表示 ===
    const statusDropdown = page.locator('[data-testid="property-status-dropdown"]')
    await expect(statusDropdown).toBeVisible({ timeout: 5_000 })
    await statusDropdown.click()
    // Dropdown の選択肢「施工中」をクリック
    await page.getByRole('option', { name: '施工中' }).click()
    // 成功トースト（i18n: property.saved = "保存しました"）
    await expect(page.getByText('保存しました').first()).toBeVisible({
      timeout: 5_000,
    })

    // === 6. 編集ボタン → モーダル → vendor 選択 → 保存 ===
    await page.locator('[data-testid="property-edit-btn"]').click()
    const editDialog = page.getByRole('dialog')
    await expect(editDialog).toBeVisible({ timeout: 5_000 })

    // VendorPicker（AutoComplete）に検索文字を入力 → サジェスト → 選択
    const vendorPicker = page.locator('[data-testid="property-vendor-picker"] input')
    await vendorPicker.click()
    await vendorPicker.fill('サンプル')
    // サジェストドロップダウンから最初の候補をクリック
    await page
      .getByRole('option', { name: /サンプル工務店/ })
      .first()
      .click({ timeout: 5_000 })

    await editDialog.getByRole('button', { name: '保存' }).click()
    await expect(editDialog).toBeHidden({ timeout: 5_000 })

    // === 7. PDF 出力 ===
    // SplitButton のメインクリックは PDF を呼び出す
    const exportBtn = page
      .locator('[data-testid="property-export-button"] button')
      .first()
    const downloadPromise = page.waitForEvent('download', { timeout: 10_000 })
    await exportBtn.click()
    const pdfDownload = await downloadPromise
    expect(pdfDownload.suggestedFilename()).toMatch(/\.pdf$/)

    // === 8. Excel 出力（SplitButton のメニューから） ===
    // PrimeVue SplitButton は隣の dropdown ボタンでメニューを開く
    const splitDropdown = page
      .locator('[data-testid="property-export-button"] button')
      .nth(1)
    await splitDropdown.click()
    const excelDownloadPromise = page.waitForEvent('download', { timeout: 10_000 })
    await page.getByRole('menuitem', { name: /Excelで出力/ }).click()
    const xlsxDownload = await excelDownloadPromise
    expect(xlsxDownload.suggestedFilename()).toMatch(/\.xlsx$/)

    // === 9. 削除ボタン → 確認ダイアログ → 削除 → 一覧へ戻る ===
    page.once('dialog', (d) => {
      // window.confirm を承諾
      void d.accept()
    })
    await page.locator('[data-testid="property-delete-btn"]').click()

    // 一覧へ戻る
    await page.waitForURL('**/property-history**', { timeout: 10_000 })
    await waitForHydration(page)

    // 削除されたパッケージのカードが消えていることを確認
    await expect(
      page.locator(`[data-testid="property-package-card-${newPkgId}"]`),
    ).toHaveCount(0)
  })

  test('PH-002: マスキング検証 — MEMBER × MEMBERS_MASKED で金額が「●●●円」表示・編集ボタン非表示', async ({
    page,
  }) => {
    const state = defaultState()
    state.asMember = true
    // 1件目を MEMBER 視点・MEMBERS_MASKED にして permissions を絞る
    state.packages = [
      makePackage({
        id: PKG_ID,
        title: 'マスキング対象パッケージ',
        visibility: 'MEMBERS_MASKED',
        // MEMBER は canViewAmount=false / canEdit=false / canDelete=false
        permissions: {
          canEdit: false,
          canDelete: false,
          canViewAmount: false,
        },
        // バックエンドは canViewAmount=false 時に金額を null で返す契約
        estimatedAmount: null,
        contractAmount: null,
        actualAmount: null,
      }),
    ]
    await setupMocks(page, state)

    // === 一覧画面: 金額が「●●●円」表示 ===
    await page.goto('/property-history?scope=organizations&scopeId=1')
    await waitForHydration(page)

    const card = page.locator(`[data-testid="property-package-card-${PKG_ID}"]`)
    await expect(card).toBeVisible({ timeout: 10_000 })
    await expect(card.getByText('●●●円')).toBeVisible({ timeout: 5_000 })

    // === 詳細ページ: 金額3項目が「●●●円」表示 ===
    await card.click()
    await page.waitForURL(`**/property-history/${PKG_ID}**`, { timeout: 10_000 })
    await waitForHydration(page)

    await expect(
      page.getByRole('heading', { name: 'マスキング対象パッケージ' }),
    ).toBeVisible({ timeout: 10_000 })

    // 金額項目（見積/契約/実施）すべてマスク表示
    const maskedSpans = page.getByText('●●●円')
    await expect(maskedSpans).toHaveCount(3, { timeout: 5_000 })

    // 編集ボタン・削除ボタン・ステータスドロップダウンが非表示
    await expect(
      page.locator('[data-testid="property-edit-btn"]'),
    ).toHaveCount(0)
    await expect(
      page.locator('[data-testid="property-delete-btn"]'),
    ).toHaveCount(0)
    await expect(
      page.locator('[data-testid="property-status-dropdown"]'),
    ).toHaveCount(0)
  })

  test('PH-003: ビュー切替 — list / timeline / gantt の SelectButton 切替で API が正しく叩かれる', async ({
    page,
  }) => {
    const state = defaultState()
    await setupMocks(page, state)

    await page.goto('/property-history?scope=organizations&scopeId=1')
    await waitForHydration(page)

    // 初期ロード: list が 1 回呼ばれる
    await expect(
      page.locator(`[data-testid="property-package-card-${PKG_ID}"]`),
    ).toBeVisible({ timeout: 10_000 })
    expect(state.callCounts.list).toBeGreaterThanOrEqual(1)
    const listCallsAfterInitial = state.callCounts.list

    // === タイムラインに切替 ===
    const switcher = page.locator('[data-testid="property-view-switch"]')
    await expect(switcher).toBeVisible()
    await switcher.getByRole('button', { name: 'タイムライン' }).click()
    // timeline API が呼ばれる
    await expect.poll(() => state.callCounts.timeline, { timeout: 5_000 }).toBeGreaterThanOrEqual(1)

    // === ガントに切替 ===
    await switcher.getByRole('button', { name: 'ガント' }).click()
    await expect.poll(() => state.callCounts.gantt, { timeout: 5_000 }).toBeGreaterThanOrEqual(1)

    // === 一覧に戻す ===
    await switcher.getByRole('button', { name: '一覧' }).click()
    await expect
      .poll(() => state.callCounts.list, { timeout: 5_000 })
      .toBeGreaterThan(listCallsAfterInitial)
  })
})
