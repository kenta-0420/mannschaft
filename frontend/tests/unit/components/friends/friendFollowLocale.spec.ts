// @vitest-environment node
/**
 * CMP-261012-0315 フレンドチームのフォロー先指定 — ロケールの試練（red）。
 *
 * 実装側が満たすべき契約:
 * - AC-12: `friends.guide.follow.step2` から「ID（数字）」相当の記述を消す（数値 ID はどこにも表示されず、
 *   利用者が入手できないため）。6 言語（ja/en/zh/ko/es/de）すべて。
 * - 新しいダイアログ文言は `friends.follow_dialog.*` に置き、6 言語すべてに空でない文字列で追加する
 *   （未翻訳なら日本語と同じ値で可）。必須キーは REQUIRED_DIALOG_KEYS を参照。
 *   自チーム指定時の案内は既存の `friends.errors.self_follow` を流用する。
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

/** FriendFollowDialog が参照する i18n キー（`friends.follow_dialog.<key>`）。 */
const REQUIRED_DIALOG_KEYS = [
  'search_label',
  'search_placeholder',
  'slug_input_label',
  'slug_input_hint',
  'no_results',
  'search_error',
  'retry',
] as const

/** 各言語で「数字の ID を入力する」を表す語。step2 に残っていてはならない。 */
const NUMERIC_ID_WORDS: Record<(typeof LOCALES)[number], RegExp> = {
  ja: /数字|番号/,
  en: /\bnumber\b|\bnumeric\b/i,
  zh: /数字|编号/,
  ko: /숫자|번호/,
  es: /número|numérico/i,
  de: /\bZahl\b|\bNummer\b/i,
}

type FriendsLocale = {
  friends: {
    guide: { follow: Record<string, string> }
    errors: Record<string, string>
    follow_dialog?: Record<string, string>
  }
}

function load(locale: string): FriendsLocale {
  return JSON.parse(
    readFileSync(resolve(process.cwd(), `app/locales/${locale}/friends.json`), 'utf8'),
  ) as FriendsLocale
}

describe('CMP-261012-0315 friends.json（6言語）', () => {
  describe.each(LOCALES)('%s', (locale) => {
    const messages = load(locale)

    it('AC-12: guide.follow.step2 に「ID（数字）」相当の記述が無い', () => {
      const step2 = messages.friends.guide.follow.step2
      expect(step2, 'step2 自体は残す（手順の欠番にしない）').toBeTruthy()
      expect(step2).not.toMatch(/\bID\b|ＩＤ/)
      expect(step2).not.toMatch(NUMERIC_ID_WORDS[locale])
    })

    it('friends.follow_dialog の必須キーが空でない文字列で揃っている', () => {
      const dialog = messages.friends.follow_dialog ?? {}
      for (const key of REQUIRED_DIALOG_KEYS) {
        expect(typeof dialog[key], `friends.follow_dialog.${key}`).toBe('string')
        expect((dialog[key] ?? '').trim().length, `friends.follow_dialog.${key}`).toBeGreaterThan(0)
      }
    })

    it('自チーム指定の案内 friends.errors.self_follow が存在する', () => {
      expect(messages.friends.errors.self_follow).toBeTruthy()
    })
  })
})
