import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import ExtendExpiryDialog from '~/components/disclosure/ExtendExpiryDialog.vue'
import type { DisclosureExport } from '~/types/disclosure'

/**
 * F09.14 Phase 4-B ExtendExpiryDialog.vue ユニットテスト。
 *
 * テスト観点:
 *  - レンダリング正常（ダイアログ + 入力フィールド + ボタン）
 *  - 過去日時 → バリデーションエラー & 送信ボタン disable
 *  - 7 年超 → バリデーションエラー & 送信ボタン disable
 *  - 正常入力 → 送信ボタン enable
 *  - 送信成功 → extendExpiry 呼び出し + extended emit + ダイアログ閉じる
 *  - キャンセル → ダイアログ閉じる
 */

const mockExtendExpiry = vi.fn()
vi.mock('~/composables/useDisclosureApi', () => ({
  useDisclosureApi: () => ({
    extendExpiry: mockExtendExpiry,
  }),
}))

const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: mockToastSuccess,
    info: vi.fn(),
    warn: vi.fn(),
    error: mockToastError,
  }),
}))

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

function buildExport(overrides: Partial<DisclosureExport> = {}): DisclosureExport {
  return {
    id: 1001,
    scopeId: '7',
    draftId: 42,
    templateCodeSnapshot: 'mlit-standard',
    templateVersionSnapshot: '1.0.0',
    outputFormat: 'PDF',
    sharedFileId: 9999,
    targetDwellingUnitId: null,
    recipientNote: null,
    sha256: 'a'.repeat(64),
    expiresAt: '2026-08-01T00:00:00',
    createdAt: '2026-05-01T00:00:00',
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockExtendExpiry.mockReset()
  mockToastSuccess.mockReset()
  mockToastError.mockReset()
})

afterEach(() => {
  const dialog = findByTestId('extend-expiry-dialog')
  dialog?.parentElement?.removeChild(dialog)
})

describe('ExtendExpiryDialog.vue', () => {
  it('open=true でダイアログがレンダリングされる', async () => {
    await mountSuspended(ExtendExpiryDialog, {
      props: { organizationId: '7', export: buildExport(), open: true },
    })
    expect(findByTestId('extend-expiry-dialog')).not.toBeNull()
    expect(findByTestId('extend-expiry-current')).not.toBeNull()
    expect(findByTestId('extend-expiry-submit')).not.toBeNull()
    expect(findByTestId('extend-expiry-cancel')).not.toBeNull()
  })

  it('初期値は現在の expiresAt + 90 日', async () => {
    const wrapper = await mountSuspended(ExtendExpiryDialog, {
      props: {
        organizationId: '7',
        export: buildExport({ expiresAt: '2026-08-01T00:00:00' }),
        open: true,
      },
    })
    const vm = wrapper.vm as unknown as { newExpiresAt: Date | null }
    expect(vm.newExpiresAt).toBeInstanceOf(Date)
    const expected = new Date('2026-08-01T00:00:00')
    expected.setDate(expected.getDate() + 90)
    expect(vm.newExpiresAt?.getTime()).toBe(expected.getTime())
  })

  it('過去日時を入力するとバリデーションエラー & 送信不可', async () => {
    const wrapper = await mountSuspended(ExtendExpiryDialog, {
      props: { organizationId: '7', export: buildExport(), open: true },
    })
    const vm = wrapper.vm as unknown as {
      newExpiresAt: Date | null
      canSubmit: boolean
      validationMessage: string | null
    }
    // 1 日前を入れる（確実に過去）
    const past = new Date()
    past.setDate(past.getDate() - 1)
    vm.newExpiresAt = past
    await wrapper.vm.$nextTick()

    expect(vm.canSubmit).toBe(false)
    expect(vm.validationMessage).toBeTruthy()
  })

  it('7 年を超える日時を入力するとバリデーションエラー & 送信不可', async () => {
    const wrapper = await mountSuspended(ExtendExpiryDialog, {
      props: { organizationId: '7', export: buildExport(), open: true },
    })
    const vm = wrapper.vm as unknown as {
      newExpiresAt: Date | null
      canSubmit: boolean
      validationMessage: string | null
    }
    // 8 年後を指定
    const tooFar = new Date()
    tooFar.setFullYear(tooFar.getFullYear() + 8)
    vm.newExpiresAt = tooFar
    await wrapper.vm.$nextTick()

    expect(vm.canSubmit).toBe(false)
    expect(vm.validationMessage).toBeTruthy()
  })

  it('正常な未来日時（1 年後）で送信可能 → extendExpiry 呼び出し → extended emit + close', async () => {
    const updated = buildExport({ expiresAt: '2027-08-01T00:00:00' })
    mockExtendExpiry.mockResolvedValueOnce(updated)

    const wrapper = await mountSuspended(ExtendExpiryDialog, {
      props: {
        organizationId: '7',
        export: buildExport({ id: 1001, expiresAt: '2026-08-01T00:00:00' }),
        open: true,
      },
    })
    const vm = wrapper.vm as unknown as {
      newExpiresAt: Date | null
      canSubmit: boolean
      handleSubmit: () => Promise<void>
    }
    const future = new Date()
    future.setFullYear(future.getFullYear() + 1)
    future.setHours(12, 0, 0, 0)
    vm.newExpiresAt = future
    await wrapper.vm.$nextTick()

    expect(vm.canSubmit).toBe(true)

    await vm.handleSubmit()
    await wrapper.vm.$nextTick()

    expect(mockExtendExpiry).toHaveBeenCalledTimes(1)
    const [exportIdArg, isoArg] = mockExtendExpiry.mock.calls[0] as [number, string]
    expect(exportIdArg).toBe(1001)
    // ISO LocalDateTime 形式 YYYY-MM-DDTHH:mm:ss
    expect(isoArg).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)

    const extendedEmits = wrapper.emitted('extended')
    expect(extendedEmits).toBeTruthy()
    expect(extendedEmits?.[0]?.[0]).toEqual(updated)

    const closeEmits = wrapper.emitted('update:open')
    expect(closeEmits?.[closeEmits.length - 1]).toEqual([false])
    expect(mockToastSuccess).toHaveBeenCalledTimes(1)
  })

  it('API 失敗時はエラートースト表示 & ダイアログは閉じない', async () => {
    mockExtendExpiry.mockRejectedValueOnce(new Error('DISCLOSURE_011'))

    const wrapper = await mountSuspended(ExtendExpiryDialog, {
      props: { organizationId: '7', export: buildExport(), open: true },
    })
    const vm = wrapper.vm as unknown as {
      newExpiresAt: Date | null
      handleSubmit: () => Promise<void>
    }
    const future = new Date()
    future.setFullYear(future.getFullYear() + 1)
    vm.newExpiresAt = future
    await wrapper.vm.$nextTick()

    await vm.handleSubmit()
    await wrapper.vm.$nextTick()

    expect(mockToastError).toHaveBeenCalledTimes(1)
    // 失敗時は extended emit / close emit が発火しない
    expect(wrapper.emitted('extended')).toBeFalsy()
    const closeEmits = wrapper.emitted('update:open') ?? []
    // open=true の通知だけは v-model で出る可能性があるが、明示 close([false]) は来ない
    expect(closeEmits.some(e => e[0] === false)).toBe(false)
  })

  it('キャンセルボタンで update:open(false) を emit', async () => {
    const wrapper = await mountSuspended(ExtendExpiryDialog, {
      props: { organizationId: '7', export: buildExport(), open: true },
    })
    const cancelBtn = findByTestId<HTMLButtonElement>('extend-expiry-cancel')
    expect(cancelBtn).not.toBeNull()
    cancelBtn!.click()
    await wrapper.vm.$nextTick()

    const closeEmits = wrapper.emitted('update:open')
    expect(closeEmits).toBeTruthy()
    expect(closeEmits?.[closeEmits.length - 1]).toEqual([false])
  })
})
