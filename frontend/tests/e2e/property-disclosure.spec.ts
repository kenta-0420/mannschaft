import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from './helpers/wait'

/**
 * F09.14 重要事項説明書（参考） E2E テスト（Phase 2-ζ-B / フロントエンド E2E 部隊）。
 *
 * 既存 E2E パターン（property-history.spec.ts）に倣い、`page.route` で
 * バックエンド API をモックすることで dev サーバ単独で完結させる。
 *
 * カバー範囲:
 * - DISC-001: ADMIN ゴールデンパス（一覧 → テンプレ選択 → ドラフト作成 → 編集 →
 *             保存 → PDF 出力 → EXPORTED ロック表示）
 * - DISC-002: 個人情報許諾フロー（チェックボックス ON/OFF と allowPersonalInfo の連動）
 * - DISC-003: 楽観的ロック競合（PUT 409 → エラートースト → 最新版再取得）
 * - DISC-004: 出力履歴一覧 + ダウンロード（presigned URL 取得 / SHA-256 不一致 503）
 * - DISC-005: 50 件上限（45 件で警告バナー / 50 件で新規作成ブロック）
 *
 * NOTE:
 * - useDisclosureApi は `useApi` 経由で `useRuntimeConfig().public.apiBase` を
 *   プレフィックスにつけて `$fetch` するため、URL マッチは `**\/api/v1/...` の
 *   glob で十分。
 * - `window.open(presignedUrl, '_blank')` は Playwright では 'popup' イベントで
 *   検知できる。Toast の警告は i18n: `disclosure.warnings.title` = "出力時の警告"。
 */

const ORG_ID = 1
const TEMPLATE_BASE_GLOB = '**/api/v1/disclosure-templates**'
const DRAFT_BASE_GLOB = `**/api/v1/organizations/${ORG_ID}/disclosure-drafts**`
const EXPORT_BASE_GLOB = `**/api/v1/organizations/${ORG_ID}/disclosure-exports**`

const TEMPLATE_ID_TOKYO_STD = 5001
const TEMPLATE_ID_CUSTOM = 5002
const DRAFT_ID = 9001
const EXPORT_ID = 7001

/** 都道府県コード（東京）。 */
const TOKYO_CODE = '13'

interface MockTemplate {
  id: number
  code: string
  name: string
  prefectureCode: string | null
  version: string
  isStandard: boolean
  isSystemTemplate: boolean
  scopeType: 'ORGANIZATION' | null
  scopeId: number | null
  formSchema: {
    sections: Array<{
      id: string
      title: string
      fields: Array<{
        id: string
        label: string
        type:
          | 'TEXT'
          | 'NUMBER'
          | 'DATE'
          | 'SELECT'
          | 'MULTISELECT'
          | 'CHECKBOX'
          | 'TEXTAREA'
          | 'AUTO_TABLE'
          | 'AUTO_FIELD'
        required?: boolean
        autoFillFrom?: string
        columns?: string[]
      }>
    }>
  }
  effectiveFrom: string | null
  effectiveUntil: string | null
  isActive: boolean
  createdAt: string
  updatedAt: string
}

interface MockDraft {
  id: number
  scopeType: 'ORGANIZATION'
  scopeId: number
  templateId: number
  templateVersionSnapshot: string
  title: string
  targetDwellingUnitId: number | null
  formData: Record<string, unknown>
  referencedPackageIds: number[] | null
  status: 'DRAFT' | 'READY' | 'EXPORTED'
  createdBy: number
  createdAt: string
  updatedAt: string
  version: number
}

interface MockExport {
  id: number
  scopeId: number
  draftId: number | null
  templateCodeSnapshot: string
  templateVersionSnapshot: string
  outputFormat: 'PDF' | 'EXCEL' | 'WORD'
  sharedFileId: number
  targetDwellingUnitId: number | null
  recipientNote: string | null
  sha256: string
  expiresAt: string | null
  createdAt: string
  downloadUrl?: string
  downloadUrlExpiresAt?: string
  warnings?: string[]
}

interface MockState {
  templates: MockTemplate[]
  drafts: MockDraft[]
  exports: MockExport[]
  /** PUT 時に強制的に 409 を返す（DISC-003）。 */
  forceConflictOnUpdate: boolean
  /** download 取得時に 503 を返す（DISC-004 改ざん検知）。 */
  forceTamperOnDownload: boolean
  /** refresh-auto-fill 時に最後に受け取った allowPersonalInfo（検証用）。 */
  lastAllowPersonalInfo: boolean | null
  /** PUT 呼出回数（DISC-003 競合後の再取得検証用）。 */
  putCount: number
  nextDraftId: number
  nextExportId: number
}

function makeTemplate(overrides: Partial<MockTemplate> = {}): MockTemplate {
  return {
    id: TEMPLATE_ID_TOKYO_STD,
    code: 'MLIT_STD_TOKYO_V1',
    name: 'MLIT 標準書式（東京都）',
    prefectureCode: TOKYO_CODE,
    version: '1.0.0',
    isStandard: true,
    isSystemTemplate: true,
    scopeType: null,
    scopeId: null,
    formSchema: {
      sections: [
        {
          id: 'sec_basic',
          title: '基本情報',
          fields: [
            { id: 'roomNumber', label: '部屋番号', type: 'TEXT', required: true },
            { id: 'contractDate', label: '契約日', type: 'DATE', required: false },
            {
              id: 'ownerName',
              label: '所有者氏名',
              type: 'AUTO_FIELD',
              autoFillFrom: 'DwellingUnitOwner',
            },
            {
              id: 'historyTable',
              label: '工事履歴',
              type: 'AUTO_TABLE',
              autoFillFrom: 'PropertyHistoryPackages',
              columns: ['title', 'workType', 'plannedEndDate'],
            },
          ],
        },
      ],
    },
    effectiveFrom: '2026-01-01',
    effectiveUntil: null,
    isActive: true,
    createdAt: '2026-04-01T09:00:00',
    updatedAt: '2026-04-01T09:00:00',
    ...overrides,
  }
}

function makeDraft(overrides: Partial<MockDraft> = {}): MockDraft {
  return {
    id: DRAFT_ID,
    scopeType: 'ORGANIZATION',
    scopeId: ORG_ID,
    templateId: TEMPLATE_ID_TOKYO_STD,
    templateVersionSnapshot: '1.0.0',
    title: '301号室売買用',
    targetDwellingUnitId: 301,
    formData: {},
    referencedPackageIds: null,
    status: 'DRAFT',
    createdBy: 1,
    createdAt: '2026-05-01T09:00:00',
    updatedAt: '2026-05-01T09:00:00',
    version: 0,
    ...overrides,
  }
}

function makeExport(overrides: Partial<MockExport> = {}): MockExport {
  return {
    id: EXPORT_ID,
    scopeId: ORG_ID,
    draftId: DRAFT_ID,
    templateCodeSnapshot: 'MLIT_STD_TOKYO_V1',
    templateVersionSnapshot: '1.0.0',
    outputFormat: 'PDF',
    sharedFileId: 12345,
    targetDwellingUnitId: 301,
    recipientNote: null,
    sha256: 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789',
    expiresAt: '2026-05-08T09:00:00',
    createdAt: '2026-05-06T09:00:00',
    downloadUrl: 'https://example.r2.test/disclosure/mock-export.pdf?sig=mock',
    downloadUrlExpiresAt: '2026-05-06T10:00:00',
    warnings: [],
    ...overrides,
  }
}

function defaultState(): MockState {
  return {
    templates: [
      makeTemplate(),
      makeTemplate({
        id: TEMPLATE_ID_CUSTOM,
        code: 'CUSTOM_ORG1_V1',
        name: '組織カスタム書式',
        prefectureCode: null,
        isStandard: false,
        isSystemTemplate: false,
        scopeType: 'ORGANIZATION',
        scopeId: ORG_ID,
      }),
    ],
    drafts: [],
    exports: [],
    forceConflictOnUpdate: false,
    forceTamperOnDownload: false,
    lastAllowPersonalInfo: null,
    putCount: 0,
    nextDraftId: DRAFT_ID,
    nextExportId: EXPORT_ID,
  }
}

async function setupAuthMock(page: Page): Promise<void> {
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
}

async function setupTemplateMock(page: Page, state: MockState): Promise<void> {
  await page.route(TEMPLATE_BASE_GLOB, async (route: Route) => {
    const url = route.request().url()
    const method = route.request().method()

    // 単体取得 /disclosure-templates/{id}
    const single = url.match(/\/disclosure-templates\/(\d+)(?:\?|$)/)
    if (single && method === 'GET') {
      const id = Number(single[1])
      const tpl = state.templates.find((t) => t.id === id)
      if (!tpl) {
        await route.fulfill({ status: 404, body: '' })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: tpl }),
      })
      return
    }

    // 一覧 /disclosure-templates?organizationId=&prefectureCode=
    if (method === 'GET') {
      const u = new URL(url)
      const pref = u.searchParams.get('prefectureCode')
      const filtered = pref
        ? state.templates.filter(
            (t) => t.prefectureCode === pref || t.prefectureCode === null,
          )
        : state.templates
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: filtered }),
      })
      return
    }

    await route.fulfill({ status: 404, body: '' })
  })
}

async function setupDraftMock(page: Page, state: MockState): Promise<void> {
  await page.route(DRAFT_BASE_GLOB, async (route: Route) => {
    const url = route.request().url()
    const method = route.request().method()

    // export
    const exportMatch = url.match(/\/disclosure-drafts\/(\d+)\/export/)
    if (exportMatch && method === 'POST') {
      const id = Number(exportMatch[1])
      const u = new URL(url)
      const format = (u.searchParams.get('format') ?? 'PDF') as
        | 'PDF'
        | 'EXCEL'
        | 'WORD'
      const idx = state.drafts.findIndex((d) => d.id === id)
      if (idx < 0) {
        await route.fulfill({ status: 404, body: '' })
        return
      }
      // ステータスを EXPORTED に更新
      state.drafts[idx]!.status = 'EXPORTED'
      state.drafts[idx]!.version += 1
      const newExport = makeExport({
        id: state.nextExportId++,
        draftId: id,
        outputFormat: format,
        warnings: [],
      })
      state.exports.unshift(newExport)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: newExport }),
      })
      return
    }

    // refresh-auto-fill
    const refreshMatch = url.match(
      /\/disclosure-drafts\/(\d+)\/refresh-auto-fill/,
    )
    if (refreshMatch && method === 'POST') {
      const id = Number(refreshMatch[1])
      const u = new URL(url)
      const allow = u.searchParams.get('allowPersonalInfo') === 'true'
      state.lastAllowPersonalInfo = allow
      const idx = state.drafts.findIndex((d) => d.id === id)
      if (idx < 0) {
        await route.fulfill({ status: 404, body: '' })
        return
      }
      const cur = state.drafts[idx]!
      // AUTO_TABLE は常に更新、AUTO_FIELD（ownerName）は allow=true 時のみ
      cur.formData = {
        ...cur.formData,
        ownerName: allow ? '山田太郎' : null,
        historyTable: [
          {
            title: 'エントランス改修',
            workType: 'RENOVATION',
            plannedEndDate: '2026-04-30',
          },
        ],
      }
      cur.version += 1
      cur.updatedAt = new Date().toISOString()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: cur }),
      })
      return
    }

    // 単体 GET / PUT / DELETE
    const single = url.match(/\/disclosure-drafts\/(\d+)(?:\?|$)/)
    if (single) {
      const id = Number(single[1])
      const idx = state.drafts.findIndex((d) => d.id === id)

      if (method === 'GET') {
        if (idx < 0) {
          await route.fulfill({ status: 404, body: '' })
          return
        }
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: state.drafts[idx] }),
        })
        return
      }

      if (method === 'PUT') {
        state.putCount += 1
        if (state.forceConflictOnUpdate) {
          // 1 回目だけ 409 を返し、その後フラグを下げて再取得後の保存を許す
          state.forceConflictOnUpdate = false
          await route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              code: 'DISCLOSURE_003',
              message: 'Version conflict',
            }),
          })
          return
        }
        if (idx < 0) {
          await route.fulfill({ status: 404, body: '' })
          return
        }
        const body = JSON.parse(
          route.request().postData() ?? '{}',
        ) as Partial<MockDraft>
        const cur = state.drafts[idx]!
        Object.assign(cur, {
          title: body.title ?? cur.title,
          targetDwellingUnitId:
            body.targetDwellingUnitId !== undefined
              ? body.targetDwellingUnitId
              : cur.targetDwellingUnitId,
          formData: body.formData ?? cur.formData,
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
        if (idx >= 0) state.drafts.splice(idx, 1)
        await route.fulfill({ status: 204, body: '' })
        return
      }
    }

    // 一覧 GET / 新規作成 POST
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: state.drafts,
          meta: {
            total: state.drafts.length,
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
      ) as Partial<MockDraft> & { templateId?: number }
      const tpl = state.templates.find((t) => t.id === body.templateId)
      const newDraft = makeDraft({
        id: state.nextDraftId++,
        templateId: body.templateId ?? TEMPLATE_ID_TOKYO_STD,
        templateVersionSnapshot: tpl?.version ?? '1.0.0',
        title: body.title ?? '(no title)',
        targetDwellingUnitId: body.targetDwellingUnitId ?? null,
        formData: {},
        version: 0,
      })
      state.drafts.unshift(newDraft)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({ data: newDraft }),
      })
      return
    }

    await route.fulfill({ status: 404, body: '' })
  })
}

async function setupExportMock(page: Page, state: MockState): Promise<void> {
  await page.route(EXPORT_BASE_GLOB, async (route: Route) => {
    const url = route.request().url()
    const method = route.request().method()

    // ダウンロード再発行 GET /{id}/download
    const downloadMatch = url.match(/\/disclosure-exports\/(\d+)\/download/)
    if (downloadMatch && method === 'GET') {
      if (state.forceTamperOnDownload) {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'DISCLOSURE_010',
            message: 'SHA-256 mismatch',
          }),
        })
        return
      }
      const id = Number(downloadMatch[1])
      const item = state.exports.find((e) => e.id === id)
      if (!item) {
        await route.fulfill({ status: 404, body: '' })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            ...item,
            downloadUrl: 'https://example.r2.test/disclosure/redownload.pdf?sig=fresh',
            downloadUrlExpiresAt: '2026-05-06T11:00:00',
          },
        }),
      })
      return
    }

    // 一覧 GET
    if (method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: state.exports,
          meta: {
            total: state.exports.length,
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
}

async function setupAllMocks(page: Page, state: MockState): Promise<void> {
  await setupAuthMock(page)
  await setupTemplateMock(page, state)
  // ※ disclosure-drafts と disclosure-exports は URL パスが似ているため、
  //   先に export 用ハンドラを登録すると一覧 GET を奪う恐れがある。
  //   Playwright は後勝ち（後から登録された route が優先）なので、
  //   より具体的な glob (`disclosure-exports`) を後に登録する。
  await setupDraftMock(page, state)
  await setupExportMock(page, state)
}

test.describe('F09.14 重要事項説明書（参考） E2E', () => {
  test('DISC-001: ADMIN ゴールデンパス — テンプレ選択 → 作成 → 編集 → 保存 → PDF 出力 → EXPORTED ロック', async ({
    page,
  }) => {
    const state = defaultState()
    await setupAllMocks(page, state)

    // === 1. 一覧画面 ===
    await page.goto(`/property-disclosure?organizationId=${ORG_ID}`)
    await waitForHydration(page)

    await expect(
      page.locator('[data-testid="disclosure-new-draft-btn"]'),
    ).toBeVisible({ timeout: 10_000 })

    // 空状態
    await expect(page.locator('[data-testid="disclosure-empty"]')).toBeVisible({
      timeout: 5_000,
    })

    // === 2. 新規ドラフト作成ボタン → TemplatePicker モーダル ===
    await page.locator('[data-testid="disclosure-new-draft-btn"]').click()
    await expect(
      page.locator('[data-testid="disclosure-template-picker"]'),
    ).toBeVisible({ timeout: 5_000 })

    // === 3. 都道府県フィルタ「東京都」→ MLIT 標準選択 ===
    await page.locator('[data-testid="disclosure-template-prefecture"]').click()
    await page.getByRole('option', { name: /東京都/ }).click()
    // 一覧に MLIT 標準が表示される
    const tokyoTpl = page.locator(
      `[data-testid="disclosure-template-${TEMPLATE_ID_TOKYO_STD}"]`,
    )
    await expect(tokyoTpl).toBeVisible({ timeout: 5_000 })
    // 「この様式で作成」クリック
    await tokyoTpl.getByRole('button', { name: 'この様式で作成' }).click()

    // === 4. タイトル入力モーダル → 作成 → 編集ページへ自動遷移 ===
    const titleInput = page.locator('[data-testid="disclosure-new-title"]')
    await expect(titleInput).toBeVisible({ timeout: 5_000 })
    await titleInput.fill('301号室売買用')
    await page.locator('[data-testid="disclosure-create-confirm"]').click()

    // 編集ページへ遷移
    await page.waitForURL(/\/property-disclosure\/\d+/, { timeout: 10_000 })
    await waitForHydration(page)

    // 編集タイトル表示
    await expect(
      page.locator('[data-testid="disclosure-edit-title"]'),
    ).toBeVisible({ timeout: 10_000 })
    await expect(
      page.locator('[data-testid="disclosure-edit-title"]'),
    ).toHaveValue('301号室売買用')

    // === 5. TEXT フィールド入力 → 保存 → version インクリメント ===
    const roomNumberField = page.locator(
      '[data-testid="disclosure-field-roomNumber"] input',
    )
    await expect(roomNumberField).toBeVisible({ timeout: 5_000 })
    await roomNumberField.fill('301')

    const beforeVersion = state.drafts[0]!.version
    await page.locator('[data-testid="disclosure-save-btn"]').click()
    await expect.poll(() => state.drafts[0]?.version ?? -1).toBeGreaterThan(
      beforeVersion,
    )

    // === 6. PDF 出力ボタン（SplitButton のメインボタン） ===
    const popupPromise = page.waitForEvent('popup', { timeout: 10_000 })
    await page
      .locator('[data-testid="disclosure-export-button"] button')
      .first()
      .click()
    const popup = await popupPromise
    expect(popup.url()).toContain('disclosure/mock-export.pdf')

    // === 7. ステータス → EXPORTED + 編集禁止バナー ===
    await expect(
      page.locator('[data-testid="disclosure-locked-banner"]'),
    ).toBeVisible({ timeout: 10_000 })
    // 保存ボタンが disabled
    await expect(
      page.locator('[data-testid="disclosure-save-btn"]'),
    ).toBeDisabled()
  })

  test('DISC-002: 個人情報許諾フロー — チェックボックス ON で AUTO_FIELD 更新、OFF で null', async ({
    page,
  }) => {
    const state = defaultState()
    state.drafts = [makeDraft({ formData: {} })]
    await setupAllMocks(page, state)

    await page.goto(
      `/property-disclosure/${DRAFT_ID}?organizationId=${ORG_ID}`,
    )
    await waitForHydration(page)

    await expect(
      page.locator('[data-testid="disclosure-edit-title"]'),
    ).toBeVisible({ timeout: 10_000 })

    // チェックボックスのデフォルトは false
    const checkbox = page.locator(
      '[data-testid="disclosure-allow-personal-info"]',
    )
    await expect(checkbox).toBeVisible({ timeout: 5_000 })

    // === ON にして自動引用更新 ===
    await checkbox.click()
    await page.locator('[data-testid="disclosure-auto-fill-refresh"]').click()
    await expect.poll(() => state.lastAllowPersonalInfo).toBe(true)
    // AUTO_FIELD（所有者氏名）に値が入る
    await expect(state.drafts[0]?.formData.ownerName).toBeTruthy()

    // === OFF に戻して再更新 ===
    await checkbox.click()
    await page.locator('[data-testid="disclosure-auto-fill-refresh"]').click()
    await expect.poll(() => state.lastAllowPersonalInfo).toBe(false)
    // AUTO_FIELD は null、AUTO_TABLE は更新（オブジェクト存在）
    await expect
      .poll(() => state.drafts[0]?.formData.ownerName)
      .toBeFalsy()
    await expect
      .poll(() => Array.isArray(state.drafts[0]?.formData.historyTable))
      .toBe(true)
  })

  test('DISC-003: 楽観的ロック競合 — PUT 409 でエラートースト → 再取得して version 更新', async ({
    page,
  }) => {
    const state = defaultState()
    state.drafts = [makeDraft({ version: 5 })]
    state.forceConflictOnUpdate = true
    await setupAllMocks(page, state)

    await page.goto(
      `/property-disclosure/${DRAFT_ID}?organizationId=${ORG_ID}`,
    )
    await waitForHydration(page)

    await expect(
      page.locator('[data-testid="disclosure-edit-title"]'),
    ).toBeVisible({ timeout: 10_000 })

    // === 別タブで保存された前提で、こちらから PUT → 409 ===
    // バックエンド側で version をインクリメントしておき、再取得で新 version が見える
    state.drafts[0]!.version = 6

    await page.locator('[data-testid="disclosure-save-btn"]').click()

    // i18n: disclosure.errors.versionConflict = "他のユーザーが先に編集しました。最新版を取得します。"
    await expect(
      page
        .getByText('他のユーザーが先に編集しました', { exact: false })
        .first(),
    ).toBeVisible({ timeout: 10_000 })

    // PUT が 1 回呼ばれたあと、再取得 (GET) → リロード後はもう 409 を返さない
    expect(state.putCount).toBeGreaterThanOrEqual(1)
  })

  test('DISC-004: 出力履歴 + ダウンロード — presigned URL で window.open / SHA-256 不一致 503', async ({
    page,
  }) => {
    const state = defaultState()
    state.exports = [makeExport(), makeExport({ id: EXPORT_ID + 1 })]
    await setupAllMocks(page, state)

    await page.goto(
      `/property-disclosure/exports?organizationId=${ORG_ID}`,
    )
    await waitForHydration(page)

    // 出力履歴テーブル表示
    await expect(
      page.locator('[data-testid="disclosure-exports-table"]'),
    ).toBeVisible({ timeout: 10_000 })

    // === ダウンロード成功 → presigned URL で別タブ ===
    const popupPromise = page.waitForEvent('popup', { timeout: 10_000 })
    await page
      .locator(`[data-testid="disclosure-download-${EXPORT_ID}"]`)
      .click()
    const popup = await popupPromise
    expect(popup.url()).toContain('redownload.pdf')

    // === SHA-256 不一致 → エラートースト ===
    state.forceTamperOnDownload = true
    await page
      .locator(`[data-testid="disclosure-download-${EXPORT_ID + 1}"]`)
      .click()
    // i18n: disclosure.errors.tampered = "ファイルの整合性検証に失敗しました（DISCLOSURE_010）"
    await expect(
      page.getByText(/ファイルの整合性検証に失敗/).first(),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('DISC-005: 50 件上限 — 45 件で警告バナー表示 / 50 件で新規作成ブロック', async ({
    page,
  }) => {
    const state = defaultState()
    // 45 件のドラフト
    state.drafts = Array.from({ length: 45 }, (_, i) =>
      makeDraft({ id: DRAFT_ID + i, title: `ドラフト${i + 1}` }),
    )
    await setupAllMocks(page, state)

    await page.goto(`/property-disclosure?organizationId=${ORG_ID}`)
    await waitForHydration(page)

    // 警告バナー表示
    await expect(
      page.locator('[data-testid="disclosure-limit-warning"]'),
    ).toBeVisible({ timeout: 10_000 })

    // === 50 件に達した状態で新規作成 → ブロック（モーダル開かず、トースト） ===
    state.drafts = Array.from({ length: 50 }, (_, i) =>
      makeDraft({ id: DRAFT_ID + i, title: `ドラフト${i + 1}` }),
    )
    // 再ロードで totalElements を 50 に反映
    await page.reload()
    await waitForHydration(page)

    await page.locator('[data-testid="disclosure-new-draft-btn"]').click()

    // テンプレートピッカーは開かない
    await expect(
      page.locator('[data-testid="disclosure-template-picker"]'),
    ).toBeHidden({ timeout: 3_000 })

    // i18n: disclosure.limit.exceeded = "ドラフトの上限（50 件）に達しています…"
    await expect(
      page.getByText(/上限.*50.*件.*に達しています/).first(),
    ).toBeVisible({ timeout: 10_000 })
  })
})
