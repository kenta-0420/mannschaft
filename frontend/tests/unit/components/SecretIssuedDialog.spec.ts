import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import QrCodeImage from '~/components/qr/QrCodeImage.vue'

/**
 * 試練（CMP-260826-1457 W0）: 共通部品 SecretIssuedDialog.vue（発行直後だけ平文の URL を見せるダイアログ）。
 *
 * 陣立て書 §3 W0「FE 共通部品 SecretIssuedDialog.vue（URL 表示・コピー・QR・閉じると二度と表示されません）」と
 * AC-18（発行直後のダイアログでのみ URL・コピー・QR を出す）の土台。各波の一覧画面はこの部品を使う。
 *
 * 部品の契約（このテストが固定する）:
 * - 置き場所: `app/components/SecretIssuedDialog.vue`
 * - props: `visible`（v-model:visible）、`url`（発行応答で受け取った平文入りの URL）、`title`（任意）
 * - 表示: URL（data-testid="secret-issued-dialog-url"）、「閉じると二度と表示されません」の警告
 *   （data-testid="secret-issued-dialog-warning"、文言は i18n の secret_token.issued_dialog.once_warning）、
 *   QR（QrCodeImage に url を渡す）、コピー（data-testid="secret-issued-dialog-copy"）、
 *   閉じる（data-testid="secret-issued-dialog-close"）
 * - 閉じると `update:visible` に false を emit する
 * - 平文の URL をブラウザの保存領域（localStorage・sessionStorage）に書かない
 *
 * ダイアログ本体は Teleport で body 直下に描画されるため、document.body から testid で探す。
 *
 * red の理由: W0 試練の時点では SecretIssuedDialog.vue が存在しない（読み込みに失敗する）。
 */

const ISSUED_URL = 'https://app.mannschaft.example/invite/cmp1457-issued-plaintext-AbCdEf0123456789xyz'

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

function getByTestId<T extends Element = HTMLElement>(testId: string): T {
  const el = findByTestId<T>(testId)
  if (!el) {
    throw new Error(`[data-testid="${testId}"] が見つかりません`)
  }
  return el
}

vi.mock('~/stores/useAuthStore', () => ({
  // app/plugins/auth.client.ts が mount 毎に loadFromStorage() を呼ぶため必須（#2609 是正と同じ扱い）
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

const writeText = vi.fn<(text: string) => Promise<void>>()

async function mountDialog(props: { visible: boolean, url: string, title?: string }) {
  const SecretIssuedDialog = (await import('~/components/SecretIssuedDialog.vue')).default
  return mountSuspended(SecretIssuedDialog, { props })
}

beforeEach(() => {
  writeText.mockReset()
  writeText.mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText },
  })
})

afterEach(() => {
  const dialog = findByTestId('secret-issued-dialog')
  dialog?.parentElement?.removeChild(dialog)
  vi.restoreAllMocks()
})

describe('SecretIssuedDialog.vue（発行時のみ平文を表示）', () => {
  it('visible=false のときは何も表示しない', async () => {
    await mountDialog({ visible: false, url: ISSUED_URL })
    expect(findByTestId('secret-issued-dialog')).toBeNull()
  })

  it('visible=true で URL と「閉じると二度と表示されません」の警告を表示する', async () => {
    await mountDialog({ visible: true, url: ISSUED_URL })

    expect(getByTestId('secret-issued-dialog-url').textContent ?? '').toContain(ISSUED_URL)
    const warning = (getByTestId('secret-issued-dialog-warning').textContent ?? '').trim()
    expect(warning.length).toBeGreaterThan(0)
    // i18n キーが未登録だと生のキー文字列が出る。それを許さない
    expect(warning).not.toContain('secret_token.issued_dialog.once_warning')
  })

  it('QR コードに発行された URL を渡す', async () => {
    const wrapper = await mountDialog({ visible: true, url: ISSUED_URL })
    await flushPromises()

    const qr = wrapper.findComponent(QrCodeImage)
    expect(qr.exists()).toBe(true)
    expect(qr.props('value')).toBe(ISSUED_URL)
  })

  it('コピー ボタンで URL をクリップボードへ書き込む', async () => {
    await mountDialog({ visible: true, url: ISSUED_URL })

    getByTestId<HTMLButtonElement>('secret-issued-dialog-copy').click()
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(ISSUED_URL)
  })

  it('閉じる ボタンで update:visible に false を emit する', async () => {
    const wrapper = await mountDialog({ visible: true, url: ISSUED_URL })

    getByTestId<HTMLButtonElement>('secret-issued-dialog-close').click()
    await flushPromises()

    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('平文の URL を localStorage・sessionStorage に書かない', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')

    const wrapper = await mountDialog({ visible: true, url: ISSUED_URL })
    getByTestId<HTMLButtonElement>('secret-issued-dialog-copy').click()
    getByTestId<HTMLButtonElement>('secret-issued-dialog-close').click()
    await flushPromises()
    wrapper.unmount()

    const leaked = setItem.mock.calls.filter(([key, value]) =>
      String(key).includes(ISSUED_URL) || String(value).includes(ISSUED_URL),
    )
    expect(leaked).toEqual([])
  })
})
