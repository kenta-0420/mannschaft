// @vitest-environment node
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import type { PageMeta } from './api'

/**
 * CMP-260912-1823 の番人。
 *
 * BE の `PagedResponse.PageMeta`（`backend/.../common/PagedResponse.java`）は
 * `total` / `page` / `size` / `totalPages` の4フィールドしか送らない。
 * にもかかわらず FE は長く `totalElements` を必須フィールドとして型付けしており、
 * 「実行時は常に undefined なのに型検査は素通りする」状態だった。
 * その結果、総件数が 0 のままページャーが表示されず、
 * 101 人目以降の時給を設定できない欠陥（CMP-260910-1555）が5巡の検分を潜り抜けた。
 *
 * ここでは2層で守る。
 *  1. 契約層: BE の正本（docs/openapi.json の PageMeta スキーマ）とフィールド名が一致すること
 *  2. 走査層: FE 全体で `meta` envelope に `totalElements` を書いた箇所が復活しないこと
 */

const FRONTEND_ROOT = resolve(__dirname, '../..')
const REPO_ROOT = resolve(FRONTEND_ROOT, '..')

/**
 * 正本 `PageMeta` のフィールド名を実行時に得るための代表値。
 *
 * `PageMeta` として型付けしてあるため、型にフィールドが増減すれば
 * `npm run typecheck` が（余剰プロパティ/不足プロパティとして）落ちる。
 * 実行時は `Object.keys` で openapi.json と突き合わせる。
 */
const PAGE_META_SAMPLE: PageMeta = {
  page: 0,
  size: 20,
  total: 0,
  totalPages: 0,
}

/** openapi.json から `PageMeta` スキーマのプロパティ名を取り出す。 */
function openApiPageMetaKeys(): string[] {
  const raw = readFileSync(join(REPO_ROOT, 'docs', 'openapi.json'), 'utf-8')
  const spec = JSON.parse(raw) as {
    components: { schemas: Record<string, { properties?: Record<string, unknown> }> }
  }
  const schema = spec.components.schemas.PageMeta
  if (!schema) throw new Error('openapi.json に PageMeta スキーマが存在しない')
  return Object.keys(schema.properties ?? {}).sort()
}

/** `app/` 配下の .ts / .vue を列挙する（自動生成の generated/ は除外）。 */
function listSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      if (entry === 'generated' || entry === 'node_modules') continue
      listSourceFiles(full, acc)
    }
    else if (/\.(ts|vue)$/.test(entry) && entry !== 'pageMeta.contract.spec.ts') {
      acc.push(full)
    }
  }
  return acc
}

/** `meta` envelope に対する `totalElements` の型宣言・読み取りを検出する正規表現。 */
const META_TOTAL_ELEMENTS_DECLARATION = /meta\??\s*:\s*\{[^}]*totalElements/
const META_TOTAL_ELEMENTS_ACCESS = /\bmeta\??\.totalElements/
/** 行頭がコメントの行（`//`・`/*`・JSDoc の継続行 `*`）。 */
const COMMENT_LINE = /^\s*(\/\/|\/\*|\*)/

/** 正本 `app/types/api.ts` の `PageMeta` 宣言本文を取り出す。 */
function canonicalPageMetaDeclaration(): string {
  const src = readFileSync(join(FRONTEND_ROOT, 'app', 'types', 'api.ts'), 'utf-8')
  const m = src.match(/export interface PageMeta \{([^}]*)\}/)
  expect(m, 'app/types/api.ts に PageMeta の宣言が存在すること').toBeTruthy()
  return (m as RegExpMatchArray)[1] as string
}

describe('PageMeta の BE 契約', () => {
  it('正本 PageMeta の宣言に totalElements が復活していない', () => {
    // 走査層の正規表現は `meta: { ... }` 形のインライン宣言しか見ないため、
    // 正本インターフェース自身への復活はここで別途止める。
    expect(canonicalPageMetaDeclaration()).not.toContain('totalElements')
  })

  it('正本 PageMeta の total は必須である（任意にすると読み側が黙って undefined を許す）', () => {
    const body = canonicalPageMetaDeclaration()
    expect(body).toContain('total: number')
    expect(body).not.toContain('total?')
  })

  it('BE の正本（openapi.json の PageMeta）とフィールド名が完全一致する', () => {
    expect(Object.keys(PAGE_META_SAMPLE).sort()).toEqual(openApiPageMetaKeys())
  })

  it('総件数のフィールド名は total であり totalElements ではない', () => {
    expect(openApiPageMetaKeys()).toContain('total')
    expect(openApiPageMetaKeys()).not.toContain('totalElements')
    expect(Object.keys(PAGE_META_SAMPLE)).not.toContain('totalElements')
  })
})

describe('meta envelope に totalElements を書いた箇所が存在しない', () => {
  // `tests/` も走査する。モックが誤った形（totalElements）で書かれていると
  // テストが実装の誤りをそのまま追認してしまい、欠陥が検分を潜り抜ける。
  const files = [
    ...listSourceFiles(join(FRONTEND_ROOT, 'app')),
    ...listSourceFiles(join(FRONTEND_ROOT, 'tests')),
  ]

  /** 走査は1度だけ行い、2種類の違反を同時に集める（ファイル数が多く再走査すると遅い）。 */
  const declarationViolations: string[] = []
  const accessViolations: string[] = []
  for (const file of files) {
    readFileSync(file, 'utf-8').split(/\r?\n/).forEach((line, i) => {
      // 解説コメントは対象外（なぜ totalElements が誤りかを書けなくなるため）
      if (COMMENT_LINE.test(line)) return
      const where = `${file.replace(FRONTEND_ROOT, '')}:${i + 1}: ${line.trim()}`
      if (META_TOTAL_ELEMENTS_DECLARATION.test(line)) declarationViolations.push(where)
      if (META_TOTAL_ELEMENTS_ACCESS.test(line)) accessViolations.push(where)
    })
  }

  it('走査対象ファイルが十分に存在する（走査自体の空振りを防ぐ）', () => {
    expect(files.length).toBeGreaterThan(100)
  })

  it('meta の型宣言に totalElements が現れない', () => {
    expect(
      declarationViolations,
      `BE は meta.total を送る。meta.totalElements は常に undefined になる:\n${declarationViolations.join('\n')}`,
    ).toEqual([])
  })

  it('meta.totalElements を読み取っている箇所が存在しない', () => {
    expect(
      accessViolations,
      `総件数は meta.total を読むこと。meta.totalElements は常に undefined でページャーが出なくなる:\n${accessViolations.join('\n')}`,
    ).toEqual([])
  })
})
