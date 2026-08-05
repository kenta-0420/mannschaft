// @vitest-environment happy-dom
//
// 検査対象は純関数と JSON だけで、Nuxt ランタイム（app context）を必要としない。
// 既定の `environment: 'nuxt'` は beforeAll で setupNuxt() を走らせて遅く・不安定なため避ける。
// ただし setup ファイル経由で読み込まれるモジュールが `document` を触るので、
// 素の node ではなく DOM のある happy-dom を使う。
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createPinia, setActivePinia } from 'pinia'

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
  minLength?: number
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
 * スキーマ上「必須」とみなすキーの集合。
 *
 * `required` 配列だけを見ても**今回の事故は検出できない**。springdoc は Bean Validation を
 * こう写す:
 *   - `@NotNull Boolean`  → `required` に載る
 *   - `@NotBlank String`  → `required` に載らず、`minLength: 1` になるだけ
 *
 * 実際 `CreateSurveyRequest.required` は `['allowMultipleSubmissions','isAnonymous']` のみで、
 * 欠落して 400 を起こした `distributionMode`（`@NotBlank`）も、ブログの `status`（同）も
 * `required` には載っていない。よって `minLength >= 1` も必須指標として扱う。
 */
function requiredKeysOf(schema: Schema): string[] {
  const fromRequired = schema.required ?? []
  const fromNotBlank = Object.entries(schema.properties ?? {})
    .filter(([, p]) => typeof p.minLength === 'number' && p.minLength >= 1)
    .map(([k]) => k)
  return [...new Set([...fromRequired, ...fromNotBlank])]
}

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

  // 2. 必須キーが欠けていないか
  for (const req of requiredKeysOf(schema)) {
    expect(
      obj[req] !== undefined && obj[req] !== null,
      `${path}.${req} は BE で必須だが送られていない。400 になる。`,
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

// --- 本番コードの読み込み（いずれも実物。spec 内に複製は作らない） ---
// dayjs の utc / timezone プラグインは本番と同じくプラグインモジュールの副作用で適用する。
await import('~/plugins/dayjs')
const { toWireCreateBody, toWireUpdateBody, toWireQuestion } = await import(
  '~/composables/useSurveyApi'
)
const { buildBlogPublishBody } = await import('~/composables/useBlogApi')
const { buildDigestPeriod } = await import('~/composables/useTimelineDigestApi')
const { useDatetime } = await import('~/composables/useDatetime')

// useDatetime は useAuthStore（Pinia）を参照する。未ログイン時は 'Asia/Tokyo' が既定。
setActivePinia(createPinia())
const datetime = useDatetime()

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
    /** 生成ダイアログと同じ手順でボディを組み立てる（期間は本番の buildDigestPeriod が作る）。 */
    function buildDialogBody(start: Date, end: Date) {
      return {
        scopeType: 'TEAM',
        scopeId: 1,
        ...buildDigestPeriod(start, end, datetime),
        digestStyle: 'TEMPLATE',
      }
    }

    it('生成ダイアログが組み立てるボディがスキーマに適合する', () => {
      assertConformsToSchema(
        'DigestGenerateRequest',
        buildDialogBody(new Date('2026-07-01T00:00:00+09:00'), new Date('2026-07-31T00:00:00+09:00')),
      )
    })

    it('期間が date-only ではなく date-time で送られる', () => {
      const body = buildDialogBody(
        new Date('2026-07-01T00:00:00+09:00'),
        new Date('2026-07-31T00:00:00+09:00'),
      )
      expect(body.periodStart).toBe('2026-07-01T00:00:00+09:00')
      expect(body.periodEnd).toBe('2026-07-31T23:59:59+09:00')
    })

    it('終端はその日の 23:59:59 まで含める（BE の期間比較は両端 inclusive）', () => {
      const body = buildDialogBody(
        new Date('2026-07-01T00:00:00+09:00'),
        new Date('2026-07-31T00:00:00+09:00'),
      )
      // 翌日 00:00:00 だと翌日ちょうどの投稿を巻き込む
      expect(body.periodEnd).not.toBe('2026-08-01T00:00:00+09:00')
      expect(body.periodEnd.endsWith('T23:59:59+09:00')).toBe(true)
    })

    it('JST 深夜でも暦日が前日にずれない（toISOString の UTC 基準に戻さない）', () => {
      // UTC では 2026-06-30T15:00Z = JST 2026-07-01 00:00。toISOString().slice(0,10) だと 06-30 になる。
      const body = buildDialogBody(
        new Date('2026-07-01T00:30:00+09:00'),
        new Date('2026-07-31T23:30:00+09:00'),
      )
      expect(body.periodStart.startsWith('2026-07-01')).toBe(true)
      expect(body.periodEnd.startsWith('2026-07-31')).toBe(true)
    })
  })

  describe('CreateQuestionRequest（DRAFT詳細の「設問を保存して公開」= addQuestion）', () => {
    it('設問追加のボディがスキーマに適合する', () => {
      assertConformsToSchema(
        'CreateQuestionRequest',
        toWireQuestion({
          questionText: '自由記述',
          questionType: 'TEXT',
          isRequired: true,
          sortOrder: 1,
        }),
      )
    })

    it('作成経路と同じ翻訳を通る（FREE_TEXT / displayOrder）', () => {
      const wire = toWireQuestion({
        questionText: 'q',
        questionType: 'TEXT',
        isRequired: true,
        sortOrder: 5,
      }) as Record<string, unknown>
      // 'TEXT' をそのまま送ると 400、sortOrder は BE が読まず displayOrder が 0 になる
      expect(wire.questionType).toBe('FREE_TEXT')
      expect(wire.displayOrder).toBe(5)
      expect(wire).not.toHaveProperty('sortOrder')
    })
  })

  describe('番人そのものの動作確認（わざと壊した入力で落ちること）', () => {
    it('スキーマに無いキーを検出する（published_at）', () => {
      expect(() =>
        assertConformsToSchema('PublishRequest', { status: 'PUBLISHED', published_at: 'x' }),
      ).toThrow(/存在しないキー/)
    })

    it('スキーマに無いキーを検出する（deadline）', () => {
      expect(() =>
        assertConformsToSchema('CreateSurveyRequest', {
          title: 't',
          isAnonymous: false,
          allowMultipleSubmissions: false,
          distributionMode: 'ALL',
          resultsVisibility: 'AFTER_RESPONSE',
          deadline: '2026-09-01T12:00:00+09:00',
        }),
      ).toThrow(/存在しないキー/)
    })

    it('@NotBlank 由来の必須（distributionMode）の欠落を検出する', () => {
      // required 配列には載らず minLength:1 だけが付く。今回 400 を起こした当の欠落。
      expect(() =>
        assertConformsToSchema('CreateSurveyRequest', {
          title: 't',
          isAnonymous: false,
          allowMultipleSubmissions: false,
          resultsVisibility: 'AFTER_RESPONSE',
        }),
      ).toThrow(/distributionMode.*必須/)
    })

    it('@NotBlank 由来の必須（status）の欠落を検出する', () => {
      expect(() => assertConformsToSchema('PublishRequest', {})).toThrow(/status.*必須/)
    })

    it('date-time が日付のみになっているのを検出する', () => {
      expect(() =>
        assertConformsToSchema('DigestGenerateRequest', {
          scopeType: 'TEAM',
          scopeId: 1,
          periodStart: '2026-07-01',
          periodEnd: '2026-07-31',
        }),
      ).toThrow(/日付のみ/)
    })

    it('入れ子（questions[]）の中の不正も検出する', () => {
      expect(() =>
        assertConformsToSchema('CreateSurveyRequest', {
          title: 't',
          isAnonymous: false,
          allowMultipleSubmissions: false,
          distributionMode: 'ALL',
          // 親側の @NotBlank 必須を満たしたうえで、入れ子だけを壊す。
          resultsVisibility: 'AFTER_RESPONSE',
          // sortOrder は FE ドメインの名前。BE は displayOrder しか受けない。
          questions: [{ questionText: 'q', questionType: 'FREE_TEXT', sortOrder: 1 }],
        }),
      ).toThrow(/questions\[0\]\.sortOrder.*存在しないキー/)
    })
  })
})
