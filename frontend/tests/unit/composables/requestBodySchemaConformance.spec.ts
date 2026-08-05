import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * 送信ボディ ↔ BE スキーマ 整合性テスト（再発防止の番人）。
 *
 * 背景: FE が BE に存在しないフィールド名（`deadline` / `published_at`）を送り、
 * 値が黙って捨てられる・400 になる事故が 3 経路で同時に起きた。手書き型と
 * `as unknown as Record<string, unknown>` キャストが型検査をすり抜けたのが原因。
 *
 * ここでは **実際に送信されるボディを組み立てる本番コード** を呼び、その結果を
 * `docs/openapi.json`（BE から生成される契約の正本）と突き合わせる。
 *
 * 検査する 3 点は、実際に起きた 3 つの事故に 1:1 で対応する:
 *   1. スキーマに無いキーを送っていないか   … `deadline` / `published_at` 型の事故
 *   2. required なキーが欠けていないか       … `distributionMode` / `status` 欠落の事故
 *   3. `format: date-time` の値が日付のみになっていないか … ダイジェスト期間の事故
 *
 * 将来別の箇所で同種の事故が起きたときも、その経路をこの spec に足せば落ちる。
 */

// --- OpenAPI 契約（BE 由来の正本）を読む ---
// vitest の root は frontend/ なので、リポジトリ直下の docs/ はひとつ上。
const openapiPath = resolve(process.cwd(), '../docs/openapi.json')

interface SchemaProperty {
  type?: string
  format?: string
  $ref?: string
  items?: SchemaProperty
}
interface Schema {
  properties?: Record<string, SchemaProperty>
  required?: string[]
}
interface OpenApiDoc {
  components: { schemas: Record<string, Schema> }
}

const openapi = JSON.parse(readFileSync(openapiPath, 'utf-8')) as OpenApiDoc

function schemaOf(name: string): Schema {
  const s = openapi.components.schemas[name]
  if (!s) throw new Error(`OpenAPI に schema '${name}' が無い。docs/openapi.json を再生成したか確認すること。`)
  return s
}

/** `$ref: '#/components/schemas/Foo'` から 'Foo' を取り出す。 */
function refName(prop: SchemaProperty | undefined): string | null {
  const ref = prop?.$ref ?? prop?.items?.$ref
  return ref ? (ref.split('/').pop() ?? null) : null
}

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/

/**
 * body が schema に適合することを検査する。
 * 入れ子（$ref / 配列 items の $ref）も再帰的に辿る。
 */
function assertConformsToSchema(schemaName: string, body: unknown, path = schemaName): void {
  const schema = schemaOf(schemaName)
  const props = schema.properties ?? {}
  expect(body, `${path} はオブジェクトであること`).toBeTypeOf('object')
  const obj = body as Record<string, unknown>

  // 1. スキーマに無いキーを送っていないか
  for (const key of Object.keys(obj)) {
    expect(
      Object.prototype.hasOwnProperty.call(props, key),
      `${path}.${key} は BE スキーマ '${schemaName}' に存在しないキー。` +
        ` 送っても黙って捨てられる。BE のフィールド名に合わせること。` +
        ` （許可: ${Object.keys(props).sort().join(', ')}）`,
    ).toBe(true)
  }

  // 2. required なキーが欠けていないか
  for (const req of schema.required ?? []) {
    expect(
      obj[req] !== undefined && obj[req] !== null,
      `${path}.${req} は BE で必須（required）だが送られていない。400 になる。`,
    ).toBe(true)
  }

  // 3. 値の形式・入れ子
  for (const [key, value] of Object.entries(obj)) {
    if (value === undefined || value === null) continue
    const prop = props[key]

    if (prop?.format === 'date-time') {
      expect(
        typeof value === 'string' && !DATE_ONLY.test(value),
        `${path}.${key} は format: date-time だが日付のみ（'${String(value)}'）。` +
          ` BE の LocalDateTime にバインドできず 400 になる。時刻＋オフセットまで送ること。`,
      ).toBe(true)
      expect(
        Number.isNaN(Date.parse(value as string)),
        `${path}.${key} ('${String(value)}') は日時としてパースできない`,
      ).toBe(false)
    }

    const nested = refName(prop)
    if (nested) {
      if (Array.isArray(value)) {
        value.forEach((v, i) => assertConformsToSchema(nested, v, `${path}.${key}[${i}]`))
      } else {
        assertConformsToSchema(nested, value, `${path}.${key}`)
      }
    }
  }
}

// --- 本番コードの読み込み（Nuxt 自動 import に依存しない純関数のみ） ---
const { toWireCreateBody, toWireUpdateBody } = await import('~/composables/useSurveyApi')
const { buildBlogPublishBody } = await import('~/composables/useBlogApi')

/** ダイジェスト期間で使う useDatetime のヘルパ相当（JST 固定・実装と同じ整形）。 */
const jstDayStart = (ymd: string) => `${ymd}T00:00:00+09:00`
const jstDayEnd = (ymd: string) => `${ymd}T23:59:59+09:00`

describe('送信ボディ ↔ BE スキーマ 整合性', () => {
  describe('CreateSurveyRequest（アンケート作成）', () => {
    it('作成ダイアログが組み立てるボディがスキーマに適合する', () => {
      const wire = toWireCreateBody({
        title: 'テストアンケート',
        description: '説明',
        isAnonymous: false,
        allowMultipleSubmissions: false,
        resultsVisibility: 'RESPONDENTS',
        unrespondedVisibility: 'CREATOR_AND_ADMIN',
        expiresAt: '2026-09-01T12:00:00+09:00',
        questions: [
          { questionText: '自由記述', questionType: 'TEXT', isRequired: true, sortOrder: 1 },
          {
            questionText: '単一選択',
            questionType: 'SINGLE_CHOICE',
            isRequired: false,
            sortOrder: 2,
            options: [
              { optionText: 'A', sortOrder: 1 },
              { optionText: 'B', sortOrder: 2 },
            ],
          },
        ],
      })
      assertConformsToSchema('CreateSurveyRequest', wire)
    })

    it('締切が BE のフィールド名 expiresAt で送られる（deadline は送らない）', () => {
      const wire = toWireCreateBody({
        title: 't',
        expiresAt: '2026-09-01T12:00:00+09:00',
      }) as Record<string, unknown>
      expect(wire.expiresAt).toBe('2026-09-01T12:00:00+09:00')
      expect(wire).not.toHaveProperty('deadline')
    })

    it('BE 必須の distributionMode が必ず載る', () => {
      const wire = toWireCreateBody({ title: 't' }) as Record<string, unknown>
      expect(wire.distributionMode).toBe('ALL')
    })

    it('設問は BE の enum 値・フィールド名（FREE_TEXT / displayOrder）へ翻訳される', () => {
      const wire = toWireCreateBody({
        title: 't',
        questions: [
          { questionText: 'q', questionType: 'TEXT', isRequired: true, sortOrder: 3 },
          { questionText: 'r', questionType: 'RATING', isRequired: true, sortOrder: 4 },
        ],
      })
      const qs = (wire as { questions?: Array<Record<string, unknown>> }).questions ?? []
      expect(qs[0]?.questionType).toBe('FREE_TEXT')
      expect(qs[0]?.displayOrder).toBe(3)
      expect(qs[0]).not.toHaveProperty('sortOrder')
      expect(qs[1]?.questionType).toBe('SCALE')
    })

    it('結果公開範囲は BE の enum 値へ翻訳される', () => {
      const be = (fe: 'RESPONDENTS' | 'AFTER_CLOSE' | 'CREATOR_ONLY' | 'ALL_MEMBERS') =>
        (toWireCreateBody({ title: 't', resultsVisibility: fe }) as Record<string, unknown>)
          .resultsVisibility
      expect(be('RESPONDENTS')).toBe('AFTER_RESPONSE')
      expect(be('AFTER_CLOSE')).toBe('AFTER_CLOSE')
      expect(be('CREATOR_ONLY')).toBe('ADMINS_ONLY')
      // BE に対応値が無い ALL_MEMBERS（旧下書きの復元経路）は最も閉じた値へ倒す
      expect(be('ALL_MEMBERS')).toBe('ADMINS_ONLY')
    })
  })

  describe('UpdateSurveyRequest（アンケート更新）', () => {
    it('更新ボディがスキーマに適合し、締切は expiresAt で送られる', () => {
      const wire = toWireUpdateBody({
        title: '更新後',
        resultsVisibility: 'RESPONDENTS',
        expiresAt: '2026-10-01T09:00:00+09:00',
      })
      assertConformsToSchema('UpdateSurveyRequest', wire)
      expect(wire).not.toHaveProperty('deadline')
    })

    it('未指定のキーは送らない（PATCH セマンティクス）', () => {
      const wire = toWireUpdateBody({ title: 'のみ' })
      expect(Object.keys(wire)).toEqual(['title'])
    })
  })

  describe('PublishRequest（ブログ公開・予約公開）', () => {
    it('今すぐ公開のボディがスキーマに適合し status を含む', () => {
      const body = buildBlogPublishBody(null, () => null)
      assertConformsToSchema('PublishRequest', body)
      expect(body.status).toBe('PUBLISHED')
    })

    it('予約公開は status=PUBLISHED + publishedAt（BE に SCHEDULED は無い）', () => {
      const body = buildBlogPublishBody(
        new Date('2026-12-01T10:00:00+09:00'),
        () => '2026-12-01T10:00:00+09:00',
      )
      assertConformsToSchema('PublishRequest', body)
      // 'SCHEDULED' は PostStatus.valueOf が例外を投げ 500 になる
      expect(body.status).toBe('PUBLISHED')
      expect(body.publishedAt).toBe('2026-12-01T10:00:00+09:00')
    })

    it('snake_case の published_at を送らない', () => {
      const body = buildBlogPublishBody(new Date(), () => '2026-12-01T10:00:00+09:00')
      expect(body).not.toHaveProperty('published_at')
    })
  })

  describe('DigestGenerateRequest（ダイジェスト生成）', () => {
    it('期間が date-time で送られる（date-only は 400）', () => {
      const body = {
        scopeType: 'TEAM',
        scopeId: 1,
        periodStart: jstDayStart('2026-07-01'),
        periodEnd: jstDayEnd('2026-07-31'),
        digestStyle: 'TEMPLATE',
      }
      assertConformsToSchema('DigestGenerateRequest', body)
    })

    it('終端は 23:59:59 まで含める（BE の期間比較は両端 inclusive）', () => {
      expect(jstDayEnd('2026-07-31')).toBe('2026-07-31T23:59:59+09:00')
    })

    it('date-only を渡すと検査が落ちる（この番人が機能していることの確認）', () => {
      expect(() =>
        assertConformsToSchema('DigestGenerateRequest', {
          scopeType: 'TEAM',
          scopeId: 1,
          periodStart: '2026-07-01',
          periodEnd: '2026-07-31',
        }),
      ).toThrow()
    })
  })

  describe('番人そのものの動作確認', () => {
    it('スキーマに無いキーを検出する', () => {
      expect(() =>
        assertConformsToSchema('PublishRequest', { status: 'PUBLISHED', published_at: 'x' }),
      ).toThrow(/存在しないキー/)
    })

    it('required 欠落を検出する', () => {
      expect(() =>
        assertConformsToSchema('DigestGenerateRequest', { scopeType: 'TEAM' }),
      ).toThrow(/必須/)
    })
  })
})
