// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { applyAccountLocaleTo, type AccountLocaleTarget } from '../../../app/utils/accountLocale'

/**
 * CMP-261007-1243: アカウント言語を正として Cookie と表示言語を揃える純粋ロジック。
 *
 * @nuxtjs/i18n の setLocale は切替先メッセージを読み終えるまで locale も Cookie も変えない。
 * そのため「setLocale を呼んだが未完了」の間も Cookie が新しい値になっていることを、
 * 完了を保留できる偽 target で確かめる。
 */
function createTarget(initial: string, cookie: string) {
  const state = { locale: initial, cookie, setLocaleCalls: [] as string[] }
  let release: () => void = () => {}
  const target: AccountLocaleTarget = {
    locale: {
      get value() {
        return state.locale
      },
    },
    setLocale: (code) => {
      state.setLocaleCalls.push(code)
      return new Promise<void>((done) => {
        release = () => {
          state.locale = code
          state.cookie = code
          done()
        }
      })
    },
    setLocaleCookie: (code) => {
      state.cookie = code
    },
  }
  return { state, target, release: () => release() }
}

describe('applyAccountLocaleTo', () => {
  it('表示言語の切替完了を待たずに Cookie をアカウント言語へ書き換える', async () => {
    const { state, target, release } = createTarget('de', 'de')
    const pending = applyAccountLocaleTo(target, 'en')
    // setLocale はまだ完了していない（メッセージ読み込み中）が、Cookie は既に en
    expect(state.cookie).toBe('en')
    expect(state.locale).toBe('de')
    release()
    await expect(pending).resolves.toBe('en')
    expect(state.locale).toBe('en')
    expect(state.cookie).toBe('en')
  })

  it('表示がアカウント言語と同じでも、ずれた Cookie をアカウント言語へ揃える', async () => {
    const { state, target } = createTarget('ja', 'de')
    await expect(applyAccountLocaleTo(target, 'ja')).resolves.toBe('ja')
    expect(state.cookie).toBe('ja')
    // 既に同じ言語なので setLocale は呼ばない
    expect(state.setLocaleCalls).toEqual([])
  })

  it.each([null, undefined, '', 'fr', 'en-US'])('未設定・非対応コード（%s）では何も変えない', async (code) => {
    const { state, target } = createTarget('de', 'de')
    await expect(applyAccountLocaleTo(target, code)).resolves.toBeNull()
    expect(state.cookie).toBe('de')
    expect(state.setLocaleCalls).toEqual([])
  })
})

describe('言語同期の入口は applyAccountLocale に一本化されている', () => {
  const appRoot = resolve(process.cwd(), 'app')
  const callers = [
    'pages/settings/language.vue',
    'pages/login.vue',
    'pages/2fa-verify.vue',
    'pages/auth/oauth/callback.vue',
    'composables/useAccountProfile.ts',
  ]
  for (const file of callers) {
    it(`${file} は applyAccountLocale を使い、setLocale を直接呼ばない`, () => {
      const source = readFileSync(resolve(appRoot, file), 'utf8')
      expect(source).toContain('applyAccountLocale(')
      expect(source).not.toMatch(/\bsetLocale\(/)
    })
  }

  it('起動時プラグインも同じ applyAccountLocaleTo を使う', () => {
    const source = readFileSync(resolve(appRoot, 'plugins/locale.client.ts'), 'utf8')
    expect(source).toContain('applyAccountLocaleTo(')
    expect(source).not.toMatch(/\.setLocale\(/)
  })
})
