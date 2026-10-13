// @vitest-environment node
import { describe, it, expect, vi } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { nextTick, ref } from 'vue'
import type { Ref, WatchStopHandle } from 'vue'
import { defaultOptions } from 'primevue/config'
import type { PrimeVueLocaleOptions } from 'primevue/config'
import { applyAccountLocaleTo, type AccountLocaleTarget } from '~/utils/accountLocale'
import type { SupportedLocale } from '~/utils/normalizeLocale'

/**
 * CMP-261007-2053: PrimeVue 既定文言の言語追従（適用ロジック）。
 *
 * 前提とする実装 API（app/utils/primevueLocales.ts）:
 * - PRIMEVUE_LOCALES: Record<'ja'|'en'|'zh'|'ko'|'es'|'de', PrimeVueLocaleOptions>
 * - resolvePrimeVueLocale(code): PrimeVueLocaleOptions
 *     PrimeVue 既定を土台に選択言語辞書で深く置換した新しいオブジェクト。未知・空は ja。
 * - applyPrimeVueLocale(config, code): void
 *     config.locale を resolvePrimeVueLocale(code) で置き換える（前言語の値を残さない）。
 * - bindPrimeVueLocale(config, localeRef): WatchStopHandle
 *     即時適用＋localeRef の変化に追従する。
 * plugin は universal（app/plugins/primevue-locale.ts）にし、SSR でも適用する。
 *
 * 実装前のため、モジュールは it 内で dynamic import する。
 */

type LocaleConfig = { locale?: PrimeVueLocaleOptions }

type PrimeVueLocalesModule = {
  PRIMEVUE_LOCALES: Record<SupportedLocale, PrimeVueLocaleOptions>
  resolvePrimeVueLocale: (code: string | null | undefined) => PrimeVueLocaleOptions
  applyPrimeVueLocale: (config: LocaleConfig, code: string | null | undefined) => void
  bindPrimeVueLocale: (config: LocaleConfig, localeRef: Ref<string>) => WatchStopHandle
}

const MODULE_PATH = '~/utils/primevueLocales'

async function loadModule(): Promise<PrimeVueLocalesModule> {
  const mod: Partial<PrimeVueLocalesModule> = await import(/* @vite-ignore */ MODULE_PATH)
  for (const name of ['PRIMEVUE_LOCALES', 'resolvePrimeVueLocale', 'applyPrimeVueLocale', 'bindPrimeVueLocale'] as const) {
    expect(mod[name], `${name} が export されていること`).toBeDefined()
  }
  return mod as PrimeVueLocalesModule
}

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

/** config.locale の全キーが PrimeVue 既定キー集合上で、指定言語の辞書値と一致しないものを列挙する */
function mismatchesAgainst(config: LocaleConfig, dict: PrimeVueLocaleOptions): string[] {
  const actual = leaves(config.locale)
  const expected = leaves(dict)
  const defaults = leaves(defaultOptions.locale)
  return [...defaults.keys()].filter(
    (k) => JSON.stringify(actual.get(k)) !== JSON.stringify(expected.get(k)),
  )
}

function freshConfig(): LocaleConfig {
  return { locale: structuredClone(defaultOptions.locale) as PrimeVueLocaleOptions }
}

describe('CMP-261007-2053 PrimeVue ロケール適用（AC-3）', () => {
  it('AC-3: de を適用すると aria.close が Schließen になる', async () => {
    const { applyPrimeVueLocale } = await loadModule()
    const config = freshConfig()
    applyPrimeVueLocale(config, 'de')
    expect(config.locale?.aria?.close).toBe('Schließen')
  })

  it('AC-3: ja→de→en と切り替えても前言語の値が残らない（全キーが現在言語の辞書値と一致）', async () => {
    const { applyPrimeVueLocale, PRIMEVUE_LOCALES } = await loadModule()
    const config = freshConfig()
    for (const code of ['ja', 'de', 'en'] as const) {
      applyPrimeVueLocale(config, code)
      expect({ code, mismatches: mismatchesAgainst(config, PRIMEVUE_LOCALES[code]) }).toEqual({
        code,
        mismatches: [],
      })
    }
  })

  it('AC-3: 既定に無い前言語由来の余計なキーや aria の部分欠落が切替後に残らない', async () => {
    const { applyPrimeVueLocale, PRIMEVUE_LOCALES } = await loadModule()
    const config = freshConfig()
    applyPrimeVueLocale(config, 'ja')
    // 浅いマージ実装だと、ja の aria がそのまま de 側へ持ち越される
    applyPrimeVueLocale(config, 'de')
    expect(config.locale?.aria?.close).toBe(PRIMEVUE_LOCALES.de.aria?.close)
    expect(config.locale?.aria?.previous).toBe(PRIMEVUE_LOCALES.de.aria?.previous)
    applyPrimeVueLocale(config, 'en')
    expect(config.locale?.aria?.close).toBe(PRIMEVUE_LOCALES.en.aria?.close)
    expect(config.locale?.today).toBe(PRIMEVUE_LOCALES.en.today)
  })

  it('AC-3: resolvePrimeVueLocale は辞書を共有せず毎回独立したオブジェクトを返す（適用先の改変が辞書を汚さない）', async () => {
    const { resolvePrimeVueLocale, PRIMEVUE_LOCALES } = await loadModule()
    const resolved = resolvePrimeVueLocale('de')
    expect(resolved).not.toBe(PRIMEVUE_LOCALES.de)
    expect(resolved.aria).not.toBe(PRIMEVUE_LOCALES.de.aria)
    if (resolved.aria) resolved.aria.close = 'mutated'
    expect(PRIMEVUE_LOCALES.de.aria?.close).toBe('Schließen')
  })
})

describe('CMP-261007-2053 未知言語のフォールバック（AC-4）', () => {
  it.each(['fr', '', 'xx-YY'])('AC-4: 未知言語 %j は ja にフォールバックする', async (code) => {
    const { resolvePrimeVueLocale } = await loadModule()
    expect(resolvePrimeVueLocale(code)).toEqual(resolvePrimeVueLocale('ja'))
  })

  it.each([null, undefined])('AC-4: %s でも例外を投げず ja を適用する', async (code) => {
    const { applyPrimeVueLocale, resolvePrimeVueLocale } = await loadModule()
    const config = freshConfig()
    expect(() => applyPrimeVueLocale(config, code)).not.toThrow()
    expect(config.locale).toEqual(resolvePrimeVueLocale('ja'))
  })

  it('AC-4: 未知言語の適用で例外を投げず、aria.close が ja 辞書値になる', async () => {
    const { applyPrimeVueLocale, PRIMEVUE_LOCALES } = await loadModule()
    const config = freshConfig()
    expect(() => applyPrimeVueLocale(config, 'fr')).not.toThrow()
    expect(config.locale?.aria?.close).toBe(PRIMEVUE_LOCALES.ja.aria?.close)
  })
})

describe('CMP-261007-2053 plugin の SSR 適用（AC-5 単体側）', () => {
  const here = dirname(fileURLToPath(import.meta.url))
  const pluginsDir = resolve(here, '../../../app/plugins')

  it('AC-5: plugin は universal（primevue-locale.ts）で、client 専用版は残っていない', () => {
    expect(existsSync(resolve(pluginsDir, 'primevue-locale.ts'))).toBe(true)
    expect(existsSync(resolve(pluginsDir, 'primevue-locale.client.ts'))).toBe(false)
  })

  it('AC-5: plugin は辞書を自前で持たず app/utils/primevueLocales の適用関数を使う', () => {
    const path = resolve(pluginsDir, 'primevue-locale.ts')
    expect(existsSync(path)).toBe(true)
    const src = existsSync(path) ? readFileSync(path, 'utf-8') : ''
    expect(src).toMatch(/primevueLocales/)
    expect(src).not.toMatch(/dayNamesShort\s*:/)
  })
})

describe('CMP-261007-2053 i18n.locale への追従（AC-6 / AC-6b）', () => {
  function fakeI18n(initial: SupportedLocale, setLocaleImpl: (code: SupportedLocale) => Promise<void>) {
    const locale = ref<string>(initial)
    let cookie: string = initial
    const target: AccountLocaleTarget = {
      locale,
      setLocale: vi.fn(setLocaleImpl),
      setLocaleCookie: vi.fn((code: SupportedLocale) => {
        cookie = code
      }),
    }
    return { locale, target, cookie: () => cookie }
  }

  it('AC-6: bindPrimeVueLocale は即時に現在言語を適用し、locale 変更に再読み込みなしで追従する', async () => {
    const { bindPrimeVueLocale, PRIMEVUE_LOCALES } = await loadModule()
    const config = freshConfig()
    const locale = ref('ja')
    const stop = bindPrimeVueLocale(config, locale)
    expect(config.locale?.aria?.close).toBe(PRIMEVUE_LOCALES.ja.aria?.close)
    locale.value = 'de'
    await nextTick()
    expect(mismatchesAgainst(config, PRIMEVUE_LOCALES.de)).toEqual([])
    locale.value = 'ko'
    await nextTick()
    expect(mismatchesAgainst(config, PRIMEVUE_LOCALES.ko)).toEqual([])
    stop()
  })

  it('AC-6: applyAccountLocale の Promise 完了時点で PrimeVue も新言語になっている', async () => {
    const { bindPrimeVueLocale, PRIMEVUE_LOCALES } = await loadModule()
    const config = freshConfig()
    const i18n = fakeI18n('ja', async (code) => {
      await new Promise((r) => setTimeout(r, 20))
      i18n.locale.value = code
    })
    const stop = bindPrimeVueLocale(config, i18n.locale)
    await applyAccountLocaleTo(i18n.target, 'es')
    await nextTick()
    expect(i18n.locale.value).toBe('es')
    expect(i18n.cookie()).toBe('es')
    expect(mismatchesAgainst(config, PRIMEVUE_LOCALES.es)).toEqual([])
    stop()
  })

  it('AC-6b: メッセージ読込が遅延している間、PrimeVue は表示中の言語（i18n.locale）と一致し続ける', async () => {
    const { bindPrimeVueLocale, PRIMEVUE_LOCALES } = await loadModule()
    const config = freshConfig()
    let release: () => void = () => undefined
    const i18n = fakeI18n('ja', (code) => new Promise<void>((r) => {
      release = () => {
        i18n.locale.value = code
        r()
      }
    }))
    const stop = bindPrimeVueLocale(config, i18n.locale)
    const pending = applyAccountLocaleTo(i18n.target, 'de')
    await nextTick()
    // 読込待ちの間は表示も PrimeVue も ja のまま（PrimeVue だけ先走らない）
    expect(i18n.locale.value).toBe('ja')
    expect(mismatchesAgainst(config, PRIMEVUE_LOCALES.ja)).toEqual([])
    release()
    await pending
    await nextTick()
    expect(mismatchesAgainst(config, PRIMEVUE_LOCALES.de)).toEqual([])
    stop()
  })

  it('AC-6b: setLocale が失敗しても Cookie・表示言語・PrimeVue 言語が食い違ったまま固まらない（失敗は握りつぶさない）', async () => {
    const { bindPrimeVueLocale, PRIMEVUE_LOCALES } = await loadModule()
    const config = freshConfig()
    const i18n = fakeI18n('ja', () => Promise.reject(new Error('メッセージ読込失敗')))
    const stop = bindPrimeVueLocale(config, i18n.locale)
    await expect(applyAccountLocaleTo(i18n.target, 'de')).rejects.toThrow('メッセージ読込失敗')
    await nextTick()
    // 表示言語は ja のまま → Cookie も PrimeVue も ja に揃っていること（Cookie だけ de で残らない）
    expect(i18n.locale.value).toBe('ja')
    expect(i18n.cookie()).toBe(i18n.locale.value)
    expect(mismatchesAgainst(config, PRIMEVUE_LOCALES.ja)).toEqual([])
    stop()
  })
})
