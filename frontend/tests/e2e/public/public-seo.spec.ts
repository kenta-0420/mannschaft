/**
 * F19.1 Phase 3 — 公開ページ SEO タグ検証 E2E。
 *
 * 検証内容:
 *  1. チームページに hreflang 6言語 + x-default タグが存在する
 *  2. チームページに JSON-LD Organization スキーマが存在する
 *  3. 組織ページに JSON-LD Organization スキーマが存在する
 *  4. 投稿詳細ページに JSON-LD Article スキーマが存在する
 *  5. canonical タグが正しいパスを指す
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
})
