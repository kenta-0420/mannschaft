// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { defaultOptions } from 'primevue/config'
import type { PrimeVueLocaleOptions } from 'primevue/config'

/**
 * CMP-261007-2053: PrimeVue 既定文言（英語）の 6 言語辞書パリティ。
 *
 * PrimeVue 4.x の既定辞書（defaultOptions.locale、aria を含む約 120 キー）を機械抽出し、
 * app/utils/primevueLocales.ts の PRIMEVUE_LOCALES がその全キーを型どおりに持つことを検証する。
 * 欠落したキーは英語既定のまま画面・読み上げに出る（ja 以外は aria が丸ごと欠落していた）。
 *
 * 辞書モジュールは実装前なので it 内で dynamic import し、各テストが assertion 段で落ちるようにする。
 */

const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
type Lang = (typeof LANGS)[number]

type Dictionaries = Record<Lang, PrimeVueLocaleOptions>

const MODULE_PATH = '~/utils/primevueLocales'

async function loadDictionaries(): Promise<Dictionaries> {
  const mod: { PRIMEVUE_LOCALES?: Dictionaries } = await import(/* @vite-ignore */ MODULE_PATH)
  if (!mod.PRIMEVUE_LOCALES) throw new Error('PRIMEVUE_LOCALES が export されていません')
  return mod.PRIMEVUE_LOCALES
}

const DEFAULT_LOCALE = defaultOptions.locale as unknown as Record<string, unknown>

/** 葉までのパス → 値 の一覧（配列は葉として扱う） */
function leaves(obj: unknown, prefix = ''): Map<string, unknown> {
  const out = new Map<string, unknown>()
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
      for (const [p, lv] of leaves(v, prefix ? `${prefix}.${k}` : k)) out.set(p, lv)
    }
    return out
  }
  out.set(prefix, obj)
  return out
}

function kindOf(v: unknown): string {
  if (Array.isArray(v)) return `array<${v.length === 0 ? 'empty' : typeof v[0]}>`
  return typeof v
}

function placeholders(s: string): string[] {
  return [...s.matchAll(/\{[^}]+\}/g)].map((m) => m[0]).sort()
}

const DEFAULT_LEAVES = leaves(DEFAULT_LOCALE)

describe('CMP-261007-2053 PrimeVue 辞書パリティ（AC-1 / AC-1b）', () => {
  it('前提: PrimeVue 既定辞書から aria を含むキーを抽出できる', () => {
    expect(DEFAULT_LEAVES.size).toBeGreaterThan(100)
    expect([...DEFAULT_LEAVES.keys()].filter((k) => k.startsWith('aria.')).length).toBeGreaterThan(40)
  })

  for (const lang of LANGS) {
    it(`AC-1: ${lang} 辞書が PrimeVue 既定の全キー（aria・ネスト含む）を持つ`, async () => {
      const dict = (await loadDictionaries())[lang]
      const keys = leaves(dict)
      const missing = [...DEFAULT_LEAVES.keys()].filter((k) => !keys.has(k))
      expect({ lang, missing }).toEqual({ lang, missing: [] })
    })

    it(`AC-1b: ${lang} 辞書の値の型が既定と一致し、文字列は非空`, async () => {
      const keys = leaves((await loadDictionaries())[lang])
      const typeMismatch: string[] = []
      const empty: string[] = []
      for (const [k, dv] of DEFAULT_LEAVES) {
        const v = keys.get(k)
        if (kindOf(v) !== kindOf(dv)) typeMismatch.push(`${k}: ${kindOf(v)} != ${kindOf(dv)}`)
        if (typeof v === 'string' && v.trim() === '') empty.push(k)
        if (Array.isArray(v) && v.some((x) => typeof x === 'string' && x.trim() === '')) empty.push(k)
      }
      expect({ lang, typeMismatch, empty }).toEqual({ lang, typeMismatch: [], empty: [] })
    })

    it(`AC-1b: ${lang} 辞書の曜日系は7件・月系は12件・firstDayOfWeek は 0〜6`, async () => {
      const dict = (await loadDictionaries())[lang] as unknown as Record<string, unknown>
      for (const key of ['dayNames', 'dayNamesShort', 'dayNamesMin']) {
        expect(dict[key], `${lang}.${key}`).toHaveLength(7)
      }
      for (const key of ['monthNames', 'monthNamesShort']) {
        expect(dict[key], `${lang}.${key}`).toHaveLength(12)
      }
      const fdow = dict.firstDayOfWeek
      expect(typeof fdow).toBe('number')
      expect(Number.isInteger(fdow)).toBe(true)
      expect(fdow as number).toBeGreaterThanOrEqual(0)
      expect(fdow as number).toBeLessThanOrEqual(6)
    })

    it(`AC-1b: ${lang} 辞書が {0} {star} {page} 等の補間変数を既定と同じだけ保持する`, async () => {
      const keys = leaves((await loadDictionaries())[lang])
      const broken: string[] = []
      for (const [k, dv] of DEFAULT_LEAVES) {
        if (typeof dv !== 'string') continue
        const want = placeholders(dv)
        if (want.length === 0) continue
        const v = keys.get(k)
        const got = typeof v === 'string' ? placeholders(v) : []
        if (JSON.stringify(got) !== JSON.stringify(want)) broken.push(`${k}: ${JSON.stringify(got)} != ${JSON.stringify(want)}`)
      }
      expect({ lang, broken }).toEqual({ lang, broken: [] })
    })
  }
})

describe('CMP-261007-2053 主要 aria・日付ラベルが翻訳済み（AC-2）', () => {
  const MUST_TRANSLATE = [
    'aria.close',
    'aria.previous',
    'aria.next',
    'aria.navigation',
    'aria.prevPageLabel',
    'aria.nextPageLabel',
    'aria.pageLabel',
    'today',
    'clear',
    'chooseMonth',
    'chooseYear',
    'nextMonth',
    'prevMonth',
  ] as const

  for (const lang of LANGS.filter((l) => l !== 'en')) {
    it(`AC-2: ${lang} の close/previous/next/navigation/ページ送り/日付ラベルが英語既定と異なる`, async () => {
      const keys = leaves((await loadDictionaries())[lang])
      const untranslated = MUST_TRANSLATE.filter((k) => {
        const v = keys.get(k)
        return v === undefined || v === DEFAULT_LEAVES.get(k)
      })
      expect({ lang, untranslated }).toEqual({ lang, untranslated: [] })
    })
  }
})
