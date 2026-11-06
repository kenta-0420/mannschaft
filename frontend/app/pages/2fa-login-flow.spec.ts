// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * ログイン時 2FA 画面の契約テスト（CMP-261012-0910 AC-13 / 28 / 38 / 52 / 53）。
 * ページ本体の実描画は実機 E2E が担う。ここでは「取りこぼすと導線が壊れる配線」を固定する。
 */
const read = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8')

const login = read('app/pages/login.vue')
const verify = read('app/pages/2fa-verify.vue')
const recovery = read('app/pages/2fa-recovery.vue')
const authApi = read('app/composables/useAuthApi.ts')

const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

function mfaKeys(lang: string): Record<string, unknown> {
  const text = read(`app/locales/${lang}/auth.json`)
  const raw = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text
  return (JSON.parse(raw) as { auth: { mfa: Record<string, unknown> } }).auth.mfa
}

function flatten(obj: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    typeof v === 'object' && v !== null
      ? flatten(v as Record<string, unknown>, `${prefix}${k}.`)
      : [`${prefix}${k}`],
  )
}

describe('login.vue: 方式情報の引継ぎ (AC-53)', () => {
  it('primaryMethod / availableMethods を 2fa-verify のクエリへ渡す', () => {
    expect(login).toContain('primaryMethod')
    expect(login).toContain('availableMethods')
    expect(login).toContain("mfaQuery.set('primary'")
    expect(login).toContain("mfaQuery.set('methods'")
  })
})

describe('2fa-verify.vue (AC-13 / AC-28 / AC-38)', () => {
  it('新 DTO { mfaSessionToken, method, code } で validate を呼び、旧 totpCode は送らない', () => {
    expect(verify).toContain('validateMfaLogin')
    expect(verify).toContain('method,')
    expect(verify).not.toContain('totpCode:')
    expect(authApi).toContain("'/api/v1/auth/2fa/validate'")
  })

  it('メールOTPは /2fa/email/send で送り、Retry-After を待ち時間に反映する', () => {
    expect(authApi).toContain("'/api/v1/auth/2fa/email/send'")
    expect(verify).toContain("get('Retry-After')")
    expect(verify).toContain('resendRemaining')
    expect(verify).toContain('expiresRemaining')
  })

  it('バックアップコードは空白・ハイフンを除去して 8 桁で送り、残り枚数を伝える', () => {
    expect(verify).toContain('normalizeBackupCode')
    expect(verify).toContain('BACKUP_CODE_LENGTH = 8')
    expect(verify).toContain('backupCodesRemaining')
    expect(verify).toContain('auth.mfa.backup_remaining_regenerate')
  })

  it('AUTH_075 / AUTH_073 ではログインへ戻す導線を出す', () => {
    expect(verify).toContain("'AUTH_075'")
    expect(verify).toContain("'AUTH_073'")
    expect(verify).toContain('data-testid="mfa-session-ended"')
  })

  it('リカバリーリンクがセッションを引き継ぐ', () => {
    expect(verify).toContain('const recoveryLink')
    expect(verify).toContain('session: mfaSessionToken.value')
    expect(verify).not.toContain('to="/2fa-recovery"')
  })

  it('リダイレクト先はサイト内パスのみ受ける', () => {
    expect(verify).toContain("raw.startsWith('/') && !raw.startsWith('//')")
  })
})

describe('ALIC-2 / ALIC-3 / UX-1: 送信ガード・文言・導線', () => {
  it('2fa-verify の handleVerify は送信中なら入口で return する（連打で validate を二重送信しない）', () => {
    expect(verify).toMatch(/if \(!mfaSessionToken\.value \|\| !canSubmit\.value \|\| loading\.value\) return/)
  })

  it('2fa-recovery の request / confirm も送信中なら入口で return する', () => {
    expect(recovery).toMatch(/if \(!mfaSessionToken\.value \|\| loading\.value\) return/)
    expect(recovery).toMatch(/if \(!recoveryToken\.value \|\| loading\.value\) return/)
  })

  it('メール方式の送信前は「送るボタンを押す」文言を出す（自動送信しない）', () => {
    expect(verify).toContain("selectedMethod.value === 'EMAIL' && !emailSent.value")
    expect(verify).toContain('auth.mfa.subtitle_email_unsent')
    expect(verify.match(/handleSendEmail\(\)/g)?.length).toBe(1) // 定義のみ。onMounted 等から呼ばない
  })

  it('バックアップコードへ切り替えたら入力欄へフォーカスする', () => {
    expect(verify).toContain('ref="backupInputRef"')
    expect(verify).toContain("method === 'BACKUP_CODE'")
    expect(verify).toContain('backupInputRef.value?.$el?.focus')
  })

  it('本人確認ダイアログのエラーは本文の先頭（説明より前）に置く', () => {
    const dialog = read('app/components/settings/SettingsTwoFactorReauthDialog.vue')
    const template = dialog.slice(dialog.indexOf('<template>'))
    expect(template.indexOf('two-fa-reauth-error')).toBeGreaterThan(-1)
    expect(template.indexOf('two-fa-reauth-error')).toBeLessThan(template.indexOf('two-fa-reauth-description'))
  })

  it('設定検索はキーワードを見る', () => {
    const hub = read('app/pages/settings/index.vue')
    expect(hub).toContain("keywords: t('settings.hub.security.keywords')")
    expect(hub).toContain('item.keywords')
  })

  it.each(LANGS)('%s: 検索語・使い方のメール方式説明・文言キーが揃い、リンク名が行き先と一致する', (lang) => {
    const settings = JSON.parse(read(`app/locales/${lang}/settings.json`)) as { settings: { hub: { security: { keywords?: string } } } }
    expect(settings.settings.hub.security.keywords).toBeTruthy()
    const help = JSON.parse(read(`app/locales/${lang}/help.json`)) as { security_help: { twofa: Record<string, string> } }
    expect(help.security_help.twofa.email_title).toBeTruthy()
    expect(help.security_help.twofa.email_desc).toBeTruthy()
    const mfa = mfaKeys(lang) as Record<string, string>
    expect(mfa.subtitle_email_unsent).toBeTruthy()
  })

  it('ja の検索語に「バックアップ」「二要素」「2段階」を含む', () => {
    const ja = JSON.parse(read('app/locales/ja/settings.json')) as { settings: { hub: { security: { keywords: string } } } }
    for (const w of ['バックアップ', '二要素', '2段階']) expect(ja.settings.hub.security.keywords).toContain(w)
    expect(mfaKeys('ja').use_recovery).toBe('メールでアカウントを回復する')
  })
})

describe('2fa-recovery.vue (AC-52 / AC-38)', () => {
  it('メールのリンクの ?token= を読み、セッション無しでも開ける', () => {
    expect(recovery).toContain('route.query.token')
    expect(recovery).toContain('!mfaSessionToken.value && !linkToken.value')
  })

  it('request / confirm は本文で送る（クエリに載せない）', () => {
    expect(authApi).toContain("body: { mfaSessionToken }")
    expect(authApi).toContain("body: { token }")
    expect(authApi).not.toContain('recovery/request?')
    expect(authApi).not.toContain('recovery/confirm?')
  })
})

describe('i18n (AC-40)', () => {
  const jaKeys = flatten(mfaKeys('ja')).sort()

  it.each(LANGS)('%s は ja と同じキー集合を持つ', (lang) => {
    expect(flatten(mfaKeys(lang)).sort()).toEqual(jaKeys)
  })

  it('ページが参照する auth.mfa.* キーは ja に存在する', () => {
    const used = new Set<string>()
    for (const src of [verify, recovery]) {
      for (const m of src.matchAll(/'(auth\.mfa\.[a-z_.]+)'/g)) used.add(m[1]!)
    }
    for (const m of verify.matchAll(/'(auth\.mfa\.[a-z_]+)'/g)) used.add(m[1]!)
    const known = new Set(jaKeys.map((k) => `auth.mfa.${k}`))
    const missing = [...used].filter((k) => !known.has(k))
    expect(missing).toEqual([])
  })

  it('ページに日本語の直書きが残っていない（コメント除く）', () => {
    for (const src of [verify, recovery]) {
      const template = src.slice(src.indexOf('<template>'))
      const stripped = template.replace(/<!--[\s\S]*?-->/g, '')
      expect(stripped).not.toMatch(/[぀-ヿ一-鿿]/)
    }
  })
})
