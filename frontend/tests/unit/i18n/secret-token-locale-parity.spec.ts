// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 試練（CMP-260826-1457 W0・AC-19）: 秘密トークンの「発行時のみ表示」に使う文言が6言語に揃っている。
 *
 * 陣立て書 §3 の共有ファイル表のとおり、全波ぶんの i18n キーは W0 が先に入れ、W1〜W2 の各波はキーを足さない。
 * 置き場所は common.json の最上位 `secret_token`（参照は `$t('secret_token.issued_dialog.title')` の形）。
 *
 * - issued_dialog: 発行直後のダイアログ（SecretIssuedDialog.vue）の文言
 * - list: 一覧画面の「平文を出さない」表示・移行による失効表示・再発行の導線（AC-18・§5 緩和策）
 *
 * ここに挙げたキーは最低限の集合で、W0 が足すのは構わない（部分集合として検査する）。
 * 訳が日本語のまま残っていないかは既存の番人 locale-no-japanese-residue.spec.ts が見る。
 *
 * red の理由: W0 試練の時点では `secret_token` がまだ無い。
 */
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

const requiredKeys = [
  'issued_dialog.title',
  'issued_dialog.once_warning',
  'issued_dialog.url_label',
  'issued_dialog.copy',
  'issued_dialog.copied',
  'issued_dialog.qr_label',
  'issued_dialog.close',
  'list.not_shown_hint',
  'list.revoked_by_migration',
  'list.reissue',
] as const

function flatten(obj: unknown, prefix = ''): Record<string, unknown> {
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    return Object.entries(obj as Record<string, unknown>).reduce<Record<string, unknown>>(
      (acc, [key, value]) => ({ ...acc, ...flatten(value, prefix ? `${prefix}.${key}` : key) }),
      {},
    )
  }
  return { [prefix]: obj }
}

function secretTokenMessages(locale: string): Record<string, unknown> {
  const file = resolve(process.cwd(), 'app', 'locales', locale, 'common.json')
  const messages = JSON.parse(readFileSync(file, 'utf8')) as { secret_token?: unknown }
  return flatten(messages.secret_token ?? {})
}

describe('secret_token（発行時のみ表示）の文言が6言語に揃っている', () => {
  it.each(locales)('%s は必要なキーをすべて持ち、値が空でない', (locale) => {
    const messages = secretTokenMessages(locale)
    const missingOrEmpty = requiredKeys.filter((key) => {
      const value = messages[key]
      return typeof value !== 'string' || value.trim().length === 0
    })
    expect({ locale, missingOrEmpty }).toEqual({ locale, missingOrEmpty: [] })
  })

  it.each(locales.filter((l) => l !== 'ja'))('%s は ja と secret_token のキー集合が一致する', (locale) => {
    const base = Object.keys(secretTokenMessages('ja')).sort()
    const keys = Object.keys(secretTokenMessages(locale)).sort()
    expect(keys).toEqual(base)
  })
})
