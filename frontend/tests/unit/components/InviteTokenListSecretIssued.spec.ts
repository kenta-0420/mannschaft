/**
 * 試練（CMP-260826-1457 W1 P1・AC-18）: 招待リンク一覧は平文・URL・QR を出さず、発行直後のダイアログでだけ見せる。
 *
 * 陣立て書 §1-A 系統1（FE `InviteTokenList.vue` が一覧から URL をコピー・tokenId で PDF を取得）と §2-2 の 6・10。
 *
 * 実装側が満たすべき契約:
 * - 一覧 API（GET `/api/v1/{teams|organizations}/{slug}/invite-tokens`）の行は平文 `token` を持たない
 *   （id / roleName / expiresAt / maxUses / usedCount / revokedAt / createdAt）。
 * - 一覧の行には URL のコピー（aria-label=`inviteToken.copy`）も QR-PDF（`inviteToken.printQrPdf`）も出さない。
 *   代わりに `secret_token.list.not_shown_hint`（再表示しない旨と再発行の案内）を表示する。
 * - 発行（POST `/api/v1/{teams|organizations}/{slug}/invite-tokens`）に成功したら、応答の `data.token` から
 *   `${window.location.origin}/invite/${token}` を作り、共通部品 `SecretIssuedDialog`（v-model:visible・url）で一度だけ見せる。
 * - チームのときだけ、ダイアログの actions スロットに `secret_token.issued_dialog.pdf_download` のボタンを置き、
 *   `useInviteTokenApi().downloadInviteTokenPdf(scopeType, scopeId, 平文)` を呼ぶ（tokenId ではなく平文。BE は POST）。
 * - ダイアログを閉じたら、平文は画面のどこにも残らない（一覧の再取得でも出ない）。
 *
 * red の理由: いまの main は一覧行に `token` を前提にしたコピー・PDF ボタンがあり、発行後は一覧を再取得するだけで
 * SecretIssuedDialog を開かない。
 */
import { defineComponent, h } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import InviteTokenList from '~/components/InviteTokenList.vue'
import SecretIssuedDialog from '~/components/SecretIssuedDialog.vue'

const { mockApi, notifySuccess, notifyError, downloadInviteTokenPdf } = vi.hoisted(() => ({
  mockApi: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  downloadInviteTokenPdf: vi.fn(),
}))

mockNuxtImport('useApi', () => () => mockApi)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useNotification', () => () => ({
  success: notifySuccess,
  error: notifyError,
  warn: vi.fn(),
  info: vi.fn(),
}))
mockNuxtImport('useInviteTokenApi', () => () => ({ downloadInviteTokenPdf }))
mockNuxtImport('useDatetime', () => () => ({ formatDate: (value: string) => value }))

const PLAINTEXT = 'Zr3x9QpL0a7_bN2-kW5yT8uE1oI4sD6fG0hJ9lK2mNc'

/** 一覧 API の 1 行（平文を持たない Summary）。 */
const SUMMARY_ROW = {
  id: 7,
  roleName: 'MEMBER',
  expiresAt: null,
  maxUses: null,
  usedCount: 0,
  revokedAt: null,
  createdAt: '2026-11-07T00:00:00',
}

/** 発行 API の応答（平文はここにだけ載る）。 */
const ISSUED = { ...SUMMARY_ROW, token: PLAINTEXT }

/** PrimeVue Dialog のテレポートを避ける外枠（スロットはそのまま描画）。 */
const DialogStub = defineComponent({
  props: { visible: { type: Boolean, default: false }, header: { type: String, default: '' } },
  emits: ['update:visible'],
  template: '<section v-if="visible"><h2>{{ header }}</h2><slot /><slot name="footer" /></section>',
})

/** 列テンプレートを行ごとに描画する DataTable の代役（既存 InviteTokenList.spec.ts と同じ形）。 */
const TableStub = defineComponent({
  props: { value: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    return () =>
      h(
        'div',
        { 'data-testid': 'invite-token-table' },
        props.value.length
          ? slots.default?.().flatMap((column) => [
              h('span', String(column.props?.header ?? '')),
              ...props.value.map((data) => {
                const children = column.children as {
                  body?: (scope: { data: unknown }) => ReturnType<typeof h>[]
                }
                return children?.body?.({ data })
              }),
            ])
          : slots.empty?.(),
      )
  },
})

const ButtonStub = defineComponent({
  props: { label: { type: String, default: '' } },
  emits: ['click'],
  template: '<button type="button" @click="$emit(\'click\')">{{ label }}</button>',
})

function routeApi() {
  mockApi.mockImplementation((_url: string, options?: { method?: string }) => {
    if (options?.method === 'POST') return Promise.resolve({ data: ISSUED })
    if (options?.method === 'DELETE') return Promise.resolve(undefined)
    return Promise.resolve({ data: [SUMMARY_ROW] })
  })
}

async function mountList(scopeType: 'team' | 'organization' = 'team') {
  const wrapper = await mountSuspended(InviteTokenList, {
    props: { scopeType, scopeId: 'private-scope' },
    global: {
      directives: { tooltip: () => {} },
      stubs: {
        DataTable: TableStub,
        Column: true,
        Dialog: DialogStub,
        Button: ButtonStub,
        RoleBadge: { props: ['role'], template: '<span>{{ role }}</span>' },
        Select: { props: ['options', 'modelValue'], template: '<select />' },
        InputNumber: { props: ['modelValue'], template: '<input />' },
        QrCodeImage: { props: ['value', 'size'], template: '<div data-testid="qr-stub" />' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountList>>

function buttonByText(wrapper: Wrapper, text: string) {
  return wrapper.findAll('button').find((button) => button.text() === text)
}

async function issueViaForm(wrapper: Wrapper) {
  const open = buttonByText(wrapper, 'inviteToken.createNew')
  expect(open, '発行フォームを開くボタンがない').toBeDefined()
  await open!.trigger('click')
  await flushPromises()
  const create = buttonByText(wrapper, 'button.create')
  expect(create, '発行ボタンがない').toBeDefined()
  await create!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  mockApi.mockReset()
  notifySuccess.mockReset()
  notifyError.mockReset()
  downloadInviteTokenPdf.mockReset()
  downloadInviteTokenPdf.mockResolvedValue(new Blob(['%PDF'], { type: 'application/pdf' }))
  routeApi()
  URL.createObjectURL = vi.fn(() => 'blob:invite-qr')
  URL.revokeObjectURL = vi.fn()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('InviteTokenList: 発行時のみ平文を見せる（CMP-260826-1457 W1 P1・AC-18）', () => {
  it('一覧の行には URL のコピーも QR-PDF も出さず、再表示しない旨の案内を出す', async () => {
    const wrapper = await mountList('team')

    expect(wrapper.find('[data-testid="invite-token-table"]').exists()).toBe(true)
    expect(wrapper.findAll('button').map((button) => button.attributes('aria-label')))
      .not.toContain('inviteToken.copy')
    expect(buttonByText(wrapper, 'inviteToken.printQrPdf')).toBeUndefined()
    expect(wrapper.html()).not.toContain('/invite/')
    expect(wrapper.text()).toContain('secret_token.list.not_shown_hint')
  })

  it('発行に成功すると SecretIssuedDialog が開き、URL は origin + /invite/ + 発行応答の平文', async () => {
    const wrapper = await mountList('team')
    await issueViaForm(wrapper)

    const dialog = wrapper.findComponent(SecretIssuedDialog)
    expect(dialog.exists(), 'SecretIssuedDialog が描画されていない').toBe(true)
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('url')).toBe(`${window.location.origin}/invite/${PLAINTEXT}`)
  })

  it('チームではダイアログから平文を渡して QR-PDF を保存する（tokenId では取得しない）', async () => {
    const wrapper = await mountList('team')
    await issueViaForm(wrapper)

    const pdf = buttonByText(wrapper, 'secret_token.issued_dialog.pdf_download')
    expect(pdf, 'ダイアログに QR-PDF の保存ボタンがない').toBeDefined()
    await pdf!.trigger('click')
    await flushPromises()

    expect(downloadInviteTokenPdf).toHaveBeenCalledTimes(1)
    expect(downloadInviteTokenPdf).toHaveBeenCalledWith('team', 'private-scope', PLAINTEXT)
  })

  it('組織ではダイアログに QR-PDF の保存ボタンを出さない', async () => {
    const wrapper = await mountList('organization')
    await issueViaForm(wrapper)

    expect(wrapper.findComponent(SecretIssuedDialog).exists()).toBe(true)
    expect(buttonByText(wrapper, 'secret_token.issued_dialog.pdf_download')).toBeUndefined()
  })

  it('ダイアログを閉じると平文は画面のどこにも残らない（一覧の再取得でも出ない）', async () => {
    const wrapper = await mountList('team')
    await issueViaForm(wrapper)
    expect(wrapper.findComponent(SecretIssuedDialog).exists()).toBe(true)

    const close = wrapper.find('[data-testid="secret-issued-dialog-close"]')
    expect(close.exists(), 'ダイアログの閉じるボタンがない').toBe(true)
    await close.trigger('click')
    await flushPromises()

    expect(wrapper.html()).not.toContain(PLAINTEXT)
  })
})
