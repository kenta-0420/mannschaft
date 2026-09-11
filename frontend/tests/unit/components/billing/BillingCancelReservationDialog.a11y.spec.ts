import { afterEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
// eslint-disable-next-line import/no-unresolved
import BillingCancelReservationDialog from '~/components/billing/BillingCancelReservationDialog.vue'

/**
 * Billing Center PR6a — E群 表示の誠実さ・a11y（AC-58・AC-59・AC-62・AC-64）の受け入れテスト（試練C・red）。
 *
 * <p>正本 05:340・05:359・04_ui_i18n.md:175。対象コンポーネントは第8隊が新設する
 * {@code BillingCancelReservationDialog.vue}（解約予約の確認ダイアログ・撤回導線を兼ねる）。
 * 現時点では未実装のため、このスペックファイル全体が import 解決に失敗して収集エラーとなる
 * （＝赤。バックエンドの「HTTP文字列で観測してコンパイルを保つ」流儀と異なり、Vite の
 * モジュール解決は実行時であるため、赤の形はコンパイルエラーではなく「テストファイル収集失敗」になる。
 * 第8隊はこのファイル名・パスへコンポーネントを実装すること）。
 *
 * <p><b>空虚な緑への備え</b>: 一画面一確認（AC-58）は「存在しないことの確認」の集合であり、
 * 陽性対照として「解約実行ボタン自体は存在する」ことを対で確認する。存在しない要素の確認だけでは
 * コンポーネントが空のdivでも通ってしまう。</p>
 */

function baseProps(overrides: Record<string, unknown> = {}) {
  return {
    contractId: '00000000-0000-7000-8000-000000000001',
    contractStatus: 'ACTIVE',
    version: 0,
    currentPeriodEnd: '2026-09-30T15:00:00Z',
    cancel: null,
    canCancel: true,
    canResume: false,
    open: true,
    ...overrides,
  }
}

const mountedWrappers: Array<{ unmount: () => void }> = []
afterEach(() => {
  while (mountedWrappers.length > 0) mountedWrappers.pop()?.unmount()
  vi.restoreAllMocks()
})

async function mountDialog(props: Record<string, unknown> = {}) {
  const wrapper = await mountSuspended(BillingCancelReservationDialog, {
    props: baseProps(props),
    attachTo: document.body,
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

describe('BillingCancelReservationDialog — 一画面一確認（AC-58）', () => {
  it('理由入力・電話番号・複数回確認・引き止め文言に相当する要素がDOMに存在しない', async () => {
    const wrapper = await mountDialog()
    const html = wrapper.html()

    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('[data-testid="cancel-reason-select"]').exists()).toBe(false)
    expect(wrapper.find('input[type="tel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="cancel-retention-offer"]').exists()).toBe(false)
    // 「本当によろしいですか」等の二段確認モーダルを想起させる要素が無いこと
    expect(wrapper.findAll('[role="dialog"]').length).toBe(1)
    expect(html).not.toContain('本当に')
  })

  it('陽性対照: 解約確定ボタン自体は存在する（空のdivでも通る偽緑を排除）', async () => {
    const wrapper = await mountDialog()
    expect(wrapper.get('[data-testid="cancel-confirm-button"]')).toBeTruthy()
  })
})

describe('BillingCancelReservationDialog — 撤回導線の表示条件（AC-59）', () => {
  it('月末前（canResume=true）は撤回ボタンが表示される', async () => {
    const wrapper = await mountDialog({
      contractStatus: 'ACTIVE',
      canCancel: false,
      canResume: true,
      cancel: { scheduledAt: '2026-09-01T00:00:00Z', endAt: '2026-09-30T15:00:00Z' },
    })
    expect(wrapper.find('[data-testid="resume-cancel-button"]').exists()).toBe(true)
  })

  it('期末を跨いだ後（canResume=false）は撤回ボタンが表示されない', async () => {
    const wrapper = await mountDialog({
      contractStatus: 'EXPIRED',
      canCancel: false,
      canResume: false,
      cancel: null,
    })
    expect(wrapper.find('[data-testid="resume-cancel-button"]').exists()).toBe(false)
  })
})

describe('BillingCancelReservationDialog — 操作中の抑止と再取得（AC-62）', () => {
  it('解約API呼び出し中は確定ボタンがdisabledになり、完了後にcontractを1回だけ再取得する', async () => {
    let resolveFetch: (() => void) | undefined
    const pending = new Promise<void>((resolve) => { resolveFetch = resolve })
    const onConfirm = vi.fn().mockReturnValue(pending)
    const onRefetch = vi.fn()

    const wrapper = await mountDialog({ onConfirm, onRefetch })
    const button = wrapper.get('[data-testid="cancel-confirm-button"]')
    await button.trigger('click')

    expect((wrapper.get('[data-testid="cancel-confirm-button"]').element as HTMLButtonElement).disabled)
      .toBe(true)

    resolveFetch?.()
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(onRefetch).toHaveBeenCalledTimes(1)
  })

  it('失敗時は明示エラー要素が表示された後にcontractを再取得する（エラーを握りつぶさない）', async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error('502'))
    const onRefetch = vi.fn()

    const wrapper = await mountDialog({ onConfirm, onRefetch })
    await wrapper.get('[data-testid="cancel-confirm-button"]').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="cancel-error"]').exists()).toBe(true)
    expect(onRefetch).toHaveBeenCalledTimes(1)
  })
})

describe('BillingCancelReservationDialog — a11y（AC-64）', () => {
  it('キーボードのみでダイアログへ到達し、Tabでconfirm内にフォーカスが閉じる（フォーカストラップ）', async () => {
    const wrapper = await mountDialog()
    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(dialog.attributes('aria-labelledby')).toBeTruthy()

    const focusable = dialog.element.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    )
    expect(focusable.length).toBeGreaterThan(0)

    // 最後のフォーカス可能要素からTabすると先頭へ戻る（トラップ）
    const last = focusable[focusable.length - 1]
    last.focus()
    await dialog.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement === focusable[0] || dialog.element.contains(document.activeElement)).toBe(true)
  })

  it('Escapeで取消でき、確定はEnter/Spaceで到達できる', async () => {
    const wrapper = await mountDialog()
    const dialog = wrapper.get('[role="dialog"]')
    await dialog.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('cancel') ?? wrapper.emitted('update:open')).toBeTruthy()
  })
})
