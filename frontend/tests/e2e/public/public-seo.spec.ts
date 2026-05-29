/**
 * F19.1 Phase 3 — 公開ページ SEO タグ検証 E2E。
 *
 * 検証内容:
 *  1. チームページに hreflang 6言語 + x-default タグが存在する
 *  2. チームページに JSON-LD Organization スキーマが存在する
 *  3. 組織ページに JSON-LD Organization スキーマが存在する
 *  4. 投稿詳細ページに JSON-LD Article スキーマが存在する
 *  5. canonical タグが正しいパスを指す
 *  6. (F21.1 §5.5) FAQPage JSON-LD（@graph #faq）と可視 FAQ の構造化×可視一致
 *
 * 【F21.1 FAQ テストの追加環境変数】
 *   - `E2E_PUBLIC_TEAM_HAS_FAQ`     : 指定チームに回答済み FAQ がある場合に set（FAQ-001/002 を有効化）
 *   - `E2E_PUBLIC_TEAM_NO_FAQ_ID`   : 回答済み FAQ が 0 件のチーム ID（FAQ-003 を有効化）
 *   - `E2E_PUBLIC_ORG_HAS_FAQ`      : 指定組織に回答済み FAQ がある場合に set（FAQ-004 を有効化）
 *
 * 【実行前提】
 * 本 spec は <strong>バックエンド + フロントエンド統合環境</strong> で実行する:
 *   1. `docker-compose up -d` で Spring Boot 8080 + MySQL + Valkey を起動
 *   2. PUBLIC かつ未 archive のチーム/組織 + 紐づく PUBLIC/PUBLISHED 状態の blog_posts 1 件を seed
 *   3. 環境変数 `E2E_PUBLIC_TEAM_ID` / `E2E_PUBLIC_POST_ID` / `E2E_PUBLIC_ORG_ID` / `E2E_PUBLIC_ORG_POST_ID` を指定して実行
 *
 * 環境変数未指定の場合は describe.skip により自動的にスキップされる。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2
 */
import { test, expect } from '@playwright/test'

// 未ログイン状態で実行
test.use({ storageState: { cookies: [], origins: [] } })

const TEAM_ID_RAW = process.env.E2E_PUBLIC_TEAM_ID
const POST_ID_RAW = process.env.E2E_PUBLIC_POST_ID
const ORG_ID_RAW = process.env.E2E_PUBLIC_ORG_ID
const ORG_POST_ID_RAW = process.env.E2E_PUBLIC_ORG_POST_ID

const RUN_INTEGRATION = TEAM_ID_RAW !== undefined && POST_ID_RAW !== undefined
const TEAM_ID = TEAM_ID_RAW !== undefined ? Number(TEAM_ID_RAW) : 0
const POST_ID = POST_ID_RAW !== undefined ? Number(POST_ID_RAW) : 0
const ORG_ID = ORG_ID_RAW !== undefined ? Number(ORG_ID_RAW) : 0
const ORG_POST_ID = ORG_POST_ID_RAW !== undefined ? Number(ORG_POST_ID_RAW) : 0

// サポートされる hreflang 値（6言語 + x-default）
const SUPPORTED_HREFLANG = ['ja', 'en', 'zh', 'ko', 'es', 'de', 'x-default'] as const

test.describe('F19.1 Phase 3 公開ページ SEO タグ検証 (BE 統合環境必須)', () => {
  test.skip(
    !RUN_INTEGRATION,
    'E2E_PUBLIC_TEAM_ID / E2E_PUBLIC_POST_ID 未設定のためスキップ（BE 統合環境でのみ実行）',
  )

  test('F19.1-SEO-001: チームページに hreflang 6言語 + x-default タグが存在する', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}`)

    // 404 でないことを確認
    const h1 = page.locator('h1').first()
    await expect(h1).toBeVisible()

    for (const lang of SUPPORTED_HREFLANG) {
      const link = await page.$(`link[rel="alternate"][hreflang="${lang}"]`)
      expect(link, `hreflang="${lang}" が存在しない`).toBeTruthy()
    }
  })

  test('F19.1-SEO-002: チームページに canonical タグが存在し正しいパスを含む', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}`)

    const canonical = await page.$('link[rel="canonical"]')
    expect(canonical).toBeTruthy()

    const href = await canonical?.getAttribute('href')
    expect(href).toContain(`/public/teams/${TEAM_ID}`)
  })

  test('F19.1-SEO-003: チームページに JSON-LD Organization スキーマが存在する（@graph 内）', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    expect(jsonLdScript).toBeTruthy()

    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    expect(schema['@context']).toBe('https://schema.org')

    // F21.1: @graph 化後は Organization は @graph 配列内に存在する
    expect(Array.isArray(schema['@graph'])).toBe(true)
    const graph = schema['@graph'] as Array<Record<string, unknown>>
    const org = graph.find((node) => node['@type'] === 'Organization')
    expect(org, 'Organization ノードが @graph に存在しない').toBeTruthy()
    expect(typeof org?.name).toBe('string')
  })

  test('F19.1-SEO-004: 組織ページに JSON-LD Organization スキーマが存在する（@graph 内）', async ({ page }) => {
    test.skip(ORG_ID === 0, 'E2E_PUBLIC_ORG_ID 未設定のためスキップ')

    await page.goto(`/public/organizations/${ORG_ID}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    expect(jsonLdScript).toBeTruthy()

    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    expect(schema['@context']).toBe('https://schema.org')

    // F21.1: @graph 化後は Organization は @graph 配列内に存在する
    expect(Array.isArray(schema['@graph'])).toBe(true)
    const graph = schema['@graph'] as Array<Record<string, unknown>>
    const org = graph.find((node) => node['@type'] === 'Organization')
    expect(org, 'Organization ノードが @graph に存在しない').toBeTruthy()
  })

  test('F19.1-SEO-005: チーム投稿詳細ページに JSON-LD Article スキーマが存在する', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}/posts/${POST_ID}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    expect(jsonLdScript).toBeTruthy()

    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    expect(schema['@context']).toBe('https://schema.org')
    expect(schema['@type']).toBe('Article')
    expect(typeof schema.headline).toBe('string')
    expect(typeof schema.datePublished).toBe('string')
    expect(schema.author?.['@type']).toBe('Person')
  })

  test('F19.1-SEO-006: 組織投稿詳細ページに JSON-LD Article スキーマが存在する', async ({ page }) => {
    test.skip(ORG_ID === 0 || ORG_POST_ID === 0, 'E2E_PUBLIC_ORG_ID / E2E_PUBLIC_ORG_POST_ID 未設定のためスキップ')

    await page.goto(`/public/organizations/${ORG_ID}/posts/${ORG_POST_ID}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    expect(jsonLdScript).toBeTruthy()

    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    expect(schema['@context']).toBe('https://schema.org')
    expect(schema['@type']).toBe('Article')
  })

  test('F19.1-SEO-007: 投稿詳細ページに hreflang 6言語 + x-default タグが存在する', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}/posts/${POST_ID}`)

    for (const lang of SUPPORTED_HREFLANG) {
      const link = await page.$(`link[rel="alternate"][hreflang="${lang}"]`)
      expect(link, `hreflang="${lang}" が存在しない`).toBeTruthy()
    }
  })

  // ─── F21.1 GEO最適化: @graph + BreadcrumbList + PostalAddress ───

  test('F21.1-GEO-001: チームページの JSON-LD に @graph があり BreadcrumbList を含む', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    expect(jsonLdScript).toBeTruthy()

    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    expect(schema['@context']).toBe('https://schema.org')
    expect(Array.isArray(schema['@graph'])).toBe(true)

    const graph = schema['@graph'] as Array<Record<string, unknown>>
    const breadcrumb = graph.find((node) => node['@type'] === 'BreadcrumbList')
    expect(breadcrumb, 'BreadcrumbList ノードが @graph に存在しない').toBeTruthy()

    const items = breadcrumb?.itemListElement as Array<Record<string, unknown>> | undefined
    expect(Array.isArray(items)).toBe(true)
    expect(items?.length).toBe(3)
    // position 1 = ホーム / position 3 = チーム名（item 無し）
    expect(items?.[0]?.position).toBe(1)
    expect(typeof items?.[0]?.item).toBe('string')
    expect(items?.[2]?.position).toBe(3)
    expect(typeof items?.[2]?.name).toBe('string')
  })

  test('F21.1-GEO-002: チームページの Organization に address(PostalAddress) が含まれる', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    const graph = schema['@graph'] as Array<Record<string, unknown>>
    const org = graph.find((node) => node['@type'] === 'Organization')
    expect(org).toBeTruthy()

    // prefecture / city を持たないテストデータでは address が省略されるためガードする。
    const address = org?.address as Record<string, unknown> | undefined
    test.skip(
      address === undefined,
      'テストチームに prefecture / city が無いため address 検証をスキップ',
    )
    expect(address?.['@type']).toBe('PostalAddress')
    expect(address?.addressCountry).toBe('JP')
  })

  test('F21.1-GEO-003: 組織ページの JSON-LD に @graph があり BreadcrumbList を含む', async ({ page }) => {
    test.skip(ORG_ID === 0, 'E2E_PUBLIC_ORG_ID 未設定のためスキップ')

    await page.goto(`/public/organizations/${ORG_ID}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    expect(jsonLdScript).toBeTruthy()

    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    expect(Array.isArray(schema['@graph'])).toBe(true)

    const graph = schema['@graph'] as Array<Record<string, unknown>>
    const breadcrumb = graph.find((node) => node['@type'] === 'BreadcrumbList')
    expect(breadcrumb, 'BreadcrumbList ノードが @graph に存在しない').toBeTruthy()

    const items = breadcrumb?.itemListElement as Array<Record<string, unknown>> | undefined
    expect(items?.length).toBe(3)
  })

  test('F21.1-GEO-004: チームページの Organization.url と canonical が同一オリジン', async ({ page }) => {
    await page.goto(`/public/teams/${TEAM_ID}`)

    // canonical link の href を取得
    const canonical = await page.$('link[rel="canonical"]')
    expect(canonical).toBeTruthy()
    const canonicalHref = await canonical?.getAttribute('href')
    expect(canonicalHref).toBeTruthy()

    // @graph 内 Organization の url を取得
    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    const graph = schema['@graph'] as Array<Record<string, unknown>>
    const org = graph.find((node) => node['@type'] === 'Organization')
    expect(org).toBeTruthy()
    const orgUrl = org?.url as string | undefined
    expect(typeof orgUrl).toBe('string')

    // canonical と Organization.url のオリジンが一致すること（単一ソース化の検証）
    const canonicalOrigin = new URL(canonicalHref ?? '').origin
    const orgOrigin = new URL(orgUrl ?? '').origin
    expect(orgOrigin).toBe(canonicalOrigin)
  })

  // ─── F21.1 §5.5 FAQ駆動GEO: FAQPage JSON-LD + 構造化×可視の一致 ───

  test("F21.1-FAQ-001: 回答済みFAQがあるチームの @graph に @type:'FAQPage'（@id が #faq）が含まれる", async ({ page }) => {
    // データ依存: 回答済み FAQ が 0 件のチームでは FAQPage ノードが付かない（FAQ-003 参照）。
    test.skip(
      process.env.E2E_PUBLIC_TEAM_HAS_FAQ === undefined,
      'E2E_PUBLIC_TEAM_HAS_FAQ 未設定（回答済みFAQ前提のテストデータが無い）のためスキップ',
    )

    await page.goto(`/public/teams/${TEAM_ID}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    expect(jsonLdScript).toBeTruthy()

    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    expect(Array.isArray(schema['@graph'])).toBe(true)

    const graph = schema['@graph'] as Array<Record<string, unknown>>
    const faqPage = graph.find((node) => node['@type'] === 'FAQPage')
    expect(faqPage, 'FAQPage ノードが @graph に存在しない').toBeTruthy()
    // @id は canonical + "#faq"
    expect(typeof faqPage?.['@id']).toBe('string')
    expect(faqPage?.['@id'] as string).toMatch(/#faq$/)
    // mainEntity は Question 配列
    const mainEntity = faqPage?.mainEntity as Array<Record<string, unknown>> | undefined
    expect(Array.isArray(mainEntity)).toBe(true)
    expect((mainEntity?.length ?? 0)).toBeGreaterThan(0)
    expect(mainEntity?.[0]?.['@type']).toBe('Question')
    expect((mainEntity?.[0]?.acceptedAnswer as Record<string, unknown> | undefined)?.['@type']).toBe('Answer')
  })

  test('F21.1-FAQ-002: 可視FAQ（public-faq-question）が FAQPage mainEntity[].name と一致する（構造化×可視の一致）', async ({ page }) => {
    test.skip(
      process.env.E2E_PUBLIC_TEAM_HAS_FAQ === undefined,
      'E2E_PUBLIC_TEAM_HAS_FAQ 未設定（回答済みFAQ前提のテストデータが無い）のためスキップ',
    )

    await page.goto(`/public/teams/${TEAM_ID}`)

    // 可視 FAQ セクションが表示されている
    await expect(page.getByTestId('public-faq-section')).toBeVisible()

    // 可視の質問テキスト集合（trim・出現順）
    const visibleQuestions = (
      await page.getByTestId('public-faq-question').allTextContents()
    ).map((s) => s.trim())
    expect(visibleQuestions.length).toBeGreaterThan(0)

    // FAQPage の mainEntity[].name 集合
    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    const graph = schema['@graph'] as Array<Record<string, unknown>>
    const faqPage = graph.find((node) => node['@type'] === 'FAQPage')
    expect(faqPage).toBeTruthy()
    const mainEntity = faqPage?.mainEntity as Array<Record<string, unknown>>
    const structuredNames = mainEntity.map((q) => String(q.name).trim())

    // 構造化データと可視内容が完全一致（集合として一致・件数一致）
    expect(structuredNames.length).toBe(visibleQuestions.length)
    expect([...structuredNames].sort()).toEqual([...visibleQuestions].sort())
  })

  test('F21.1-FAQ-003: 回答済みFAQ 0件のチームでは @graph に FAQPage ノードが無い（Organization+BreadcrumbList のみ）', async ({ page }) => {
    // データ依存: 回答済み FAQ が 0 件のチーム ID を指定して実行する。
    const noFaqTeamRaw = process.env.E2E_PUBLIC_TEAM_NO_FAQ_ID
    test.skip(
      noFaqTeamRaw === undefined,
      'E2E_PUBLIC_TEAM_NO_FAQ_ID 未設定（FAQ 0件チームのテストデータが無い）のためスキップ',
    )

    await page.goto(`/public/teams/${Number(noFaqTeamRaw)}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    expect(jsonLdScript).toBeTruthy()

    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    expect(Array.isArray(schema['@graph'])).toBe(true)

    const graph = schema['@graph'] as Array<Record<string, unknown>>
    // FAQPage ノードは存在しない
    expect(graph.find((node) => node['@type'] === 'FAQPage')).toBeUndefined()
    // Organization と BreadcrumbList は存在する
    expect(graph.find((node) => node['@type'] === 'Organization')).toBeTruthy()
    expect(graph.find((node) => node['@type'] === 'BreadcrumbList')).toBeTruthy()

    // 可視 FAQ セクションも描画されない
    await expect(page.getByTestId('public-faq-section')).toHaveCount(0)
  })

  test("F21.1-FAQ-004: 回答済みFAQがある組織の @graph に @type:'FAQPage'（@id が #faq）が含まれる", async ({ page }) => {
    test.skip(
      ORG_ID === 0 || process.env.E2E_PUBLIC_ORG_HAS_FAQ === undefined,
      'E2E_PUBLIC_ORG_ID / E2E_PUBLIC_ORG_HAS_FAQ 未設定のためスキップ',
    )

    await page.goto(`/public/organizations/${ORG_ID}`)

    const jsonLdScript = await page.$('script[type="application/ld+json"]')
    expect(jsonLdScript).toBeTruthy()

    const content = await jsonLdScript?.textContent()
    const schema = JSON.parse(content ?? '{}')
    expect(Array.isArray(schema['@graph'])).toBe(true)

    const graph = schema['@graph'] as Array<Record<string, unknown>>
    const faqPage = graph.find((node) => node['@type'] === 'FAQPage')
    expect(faqPage, 'FAQPage ノードが @graph に存在しない').toBeTruthy()
    expect(faqPage?.['@id'] as string).toMatch(/#faq$/)
    const mainEntity = faqPage?.mainEntity as Array<Record<string, unknown>> | undefined
    expect(Array.isArray(mainEntity)).toBe(true)
    expect((mainEntity?.length ?? 0)).toBeGreaterThan(0)
  })
})
