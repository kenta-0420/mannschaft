import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import Button from 'primevue/button'
import DataTable from 'primevue/datatable'
import Dialog from 'primevue/dialog'
import { useAuthStore } from '~/stores/useAuthStore'
import { useScopeStore } from '~/stores/useScopeStore'
import { useTeamStore } from '~/stores/useTeamStore'
import { useOrganizationStore } from '~/stores/useOrganizationStore'
import ReceiptsPage from '~/pages/admin/receipts.vue'
import type { ReceiptResponse } from '~/types/receipt'

// 通信境界だけをモックする。ページ、表示部品、scope/所属/本人のストアは本物を使う。
const receipt: ReceiptResponse = {
  id: 101, receiptNumber: 'R-101', status: 'ISSUED', recipientName: '受領者',
  recipientPostalCode: null, recipientAddress: null, issuerName: '発行者',
  issuerPostalCode: null, issuerAddress: null, issuerPhone: null,
  isQualifiedInvoice: false, invoiceRegistrationNumber: null, description: '会費',
  amount: 1100, taxRate: 10, taxAmount: 100, amountExclTax: 1000, lineItems: [],
  paymentMethodLabel: null, paymentDate: '2026-10-03', issuedAt: '2026-10-03T10:00:00',
  issuedBy: null, sealStamped: false, sealStampLogId: null, pdfStatus: null,
  pdfDownloadUrl: null, memberPaymentId: null, scheduleId: null, isVoided: false,
  voidedAt: null, voidedBy: null, voidedReason: null, warnings: [],
}
let pendingList: Promise<void> | null = null
let failList = false
const api = vi.fn(async (path: string, options?: { method?: string; body?: unknown }) => {
  if (path.startsWith('/api/v1/admin/receipts?') && !options?.method) {
    const wait = pendingList
    const failure = failList
    if (wait) await wait
    if (failure) throw { statusCode: 503 }
    const id = new URL(path, 'https://example.invalid').searchParams.get('scopeId') === '12' ? 101 : 202
    return { data: [{ ...receipt, id, receiptNumber: `R-${id}` }], meta: { total: 1, page: 0, size: 20, totalPages: 1 } }
  }
  if (path.includes('/void?')) return { data: { ...receipt, isVoided: true } }
  throw new Error(`Unexpected API: ${path}`)
})
mockNuxtImport('useApi', () => () => api)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

async function mountPage() {
  const wrapper = await mountSuspended(ReceiptsPage, { global: { stubs: { teleport: true } } })
  await flushPromises()
  return wrapper
}

type PageWrapper = Awaited<ReturnType<typeof mountPage>>
function voidButtons(wrapper: PageWrapper) {
  return wrapper.findAllComponents(Button).filter(button => button.props('label') === 'receipt.list.action.void')
}
function voidDialog(wrapper: PageWrapper) {
  const dialog = wrapper.findAllComponents(Dialog).find(item => item.props('header') === 'receipt.list.dialog.voidTitle')
  if (!dialog) throw new Error('無効化ダイアログが見つからない')
  return dialog
}
function selectScope(type: 'team' | 'organization', role: string) {
  if (type === 'team') {
    useTeamStore().myTeams = [{ id: 12, slug: 'alpha', name: 'チームA', nickname1: null, iconUrl: null, role, template: 'OTHER', memberCount: 1 }]
    useScopeStore().setTeamScope(12, 'チームA')
  }
  else {
    useOrganizationStore().myOrganizations = [{ id: 7, slug: 'beta', name: '組織B', nickname1: null, iconUrl: null, role, orgType: 'OTHER', memberCount: 1 }]
    useScopeStore().setOrganizationScope(7, '組織B')
  }
}

beforeAll(async () => {
  const warmup = await mountPage()
  warmup.unmount()
})
beforeEach(() => {
  api.mockClear()
  pendingList = null
  failList = false
  useScopeStore().clear()
  useTeamStore().clear()
  useOrganizationStore().clear()
  useAuthStore().user = { id: 1, email: 'test@example.invalid', fullName: '利用者', profileImageUrl: null }
})

describe('CMP1017: 領収書無効化の表示と送信境界', () => {
  it.each(['team', 'organization'] as const)('%s の所属ADMINは理由を入力して当該scopeの領収書だけを無効化する', async (type) => {
    selectScope(type, 'ADMIN')
    const wrapper = await mountPage()
    expect(voidButtons(wrapper)).toHaveLength(1)
    await voidButtons(wrapper)[0]!.trigger('click')
    await flushPromises()
    expect(voidDialog(wrapper).props('visible')).toBe(true)
    await voidDialog(wrapper).get('textarea').setValue('金額誤記')
    const submit = voidDialog(wrapper).findAllComponents(Button).find(button => button.props('label') === 'receipt.list.dialog.voidSubmit')
    if (!submit) throw new Error('無効化送信ボタンが見つからない')
    await submit.trigger('click')
    await flushPromises()
    expect(api.mock.calls.filter(([path]) => path.includes('/void?'))).toEqual([
      [`/api/v1/admin/receipts/${type === 'team' ? 101 : 202}/void?scopeType=${type === 'team' ? 'TEAM' : 'ORGANIZATION'}&scopeId=${type === 'team' ? 12 : 7}`, { method: 'POST', body: { reason: '金額誤記' } }],
    ])
    wrapper.unmount()
  })

  it.each(['DEPUTY_ADMIN', 'MEMBER', 'SYSTEM_ADMIN', 'UNKNOWN'])('%s には無効化操作を表示しない', async (role) => {
    selectScope('team', role)
    const wrapper = await mountPage()
    expect(voidButtons(wrapper)).toHaveLength(0)
    expect(api.mock.calls.some(([path]) => path.includes('/void?'))).toBe(false)
    wrapper.unmount()
  })

  it('プラットフォームSYSとscopeADMINの兼任も、新UIの無効化操作は非表示（BEの兼任許可とは別契約）', async () => {
    selectScope('team', 'ADMIN')
    useAuthStore().user!.systemRole = 'SYSTEM_ADMIN'
    const wrapper = await mountPage()
    expect(voidButtons(wrapper)).toHaveLength(0)
    wrapper.unmount()
  })

  it('未確定/個人scopeや所属一覧未確認では無効化操作を表示しない', async () => {
    const wrapper = await mountPage()
    expect(voidButtons(wrapper)).toHaveLength(0)
    expect(api).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('所属一覧を再確認中は、以前のADMIN行が残っていても無効化操作を表示しない', async () => {
    selectScope('team', 'ADMIN')
    useTeamStore().loading = true
    const wrapper = await mountPage()
    expect(voidButtons(wrapper)).toHaveLength(0)
    useTeamStore().loading = false
    await flushPromises()
    expect(voidButtons(wrapper)).toHaveLength(1)
    wrapper.unmount()
  })

  it('権限を失った直前のボタンイベントでもopenハンドラが無効化ダイアログを開かない', async () => {
    selectScope('team', 'ADMIN')
    const wrapper = await mountPage()
    const oldButton = voidButtons(wrapper)[0]!
    useTeamStore().myTeams[0]!.role = 'DEPUTY_ADMIN'
    oldButton.vm.$emit('click')
    await flushPromises()
    expect(voidDialog(wrapper).props('visible')).toBe(false)
    wrapper.unmount()
  })

  it('ダイアログを開いた後に団体が変わっても旧領収書を新scopeで送信しない', async () => {
    selectScope('team', 'ADMIN')
    const wrapper = await mountPage()
    await voidButtons(wrapper)[0]!.trigger('click')
    await flushPromises()
    await voidDialog(wrapper).get('textarea').setValue('金額誤記')
    const submit = voidDialog(wrapper).findAllComponents(Button).find(button => button.props('label') === 'receipt.list.dialog.voidSubmit')
    if (!submit) throw new Error('無効化送信ボタンが見つからない')
    selectScope('organization', 'ADMIN')
    submit.vm.$emit('click')
    await flushPromises()
    expect(api.mock.calls.some(([path]) => path.includes('/void?'))).toBe(false)
    wrapper.unmount()
  })

  it('再取得中/失敗後の古い行では無効化を表示せず、成功した同scopeの一覧だけを使う', async () => {
    selectScope('team', 'ADMIN')
    const wrapper = await mountPage()
    expect(voidButtons(wrapper)).toHaveLength(1)
    let finish: () => void = () => {}
    pendingList = new Promise<void>((resolve) => { finish = resolve })
    failList = true
    wrapper.getComponent(DataTable).vm.$emit('page', { page: 0, rows: 20 })
    await flushPromises()
    expect(voidButtons(wrapper)).toHaveLength(0)
    finish()
    await flushPromises()
    expect(voidButtons(wrapper)).toHaveLength(0)
    wrapper.unmount()
  })

  it('同じ数値IDでもTEAMからORGへ切り替えれば一覧を取り直す', async () => {
    selectScope('team', 'ADMIN')
    const wrapper = await mountPage()
    useOrganizationStore().myOrganizations = [{ id: 12, slug: 'gamma', name: '組織C', nickname1: null, iconUrl: null, role: 'ADMIN', orgType: 'OTHER', memberCount: 1 }]
    useScopeStore().setOrganizationScope(12, '組織C')
    await flushPromises()
    expect(api.mock.calls.some(([path]) => path.includes('scopeType=ORGANIZATION&scopeId=12'))).toBe(true)
    wrapper.unmount()
  })

  it('旧団体Aの遅延一覧は、新団体Bの成功一覧と無効化対象を上書きしない', async () => {
    selectScope('team', 'ADMIN')
    let finish: () => void = () => {}
    pendingList = new Promise<void>((resolve) => { finish = resolve })
    const wrapper = await mountPage()
    expect(voidButtons(wrapper)).toHaveLength(0)
    pendingList = null
    selectScope('organization', 'ADMIN')
    await flushPromises()
    expect(wrapper.text()).toContain('R-202')
    finish()
    await flushPromises()
    expect(wrapper.text()).toContain('R-202')
    expect(wrapper.text()).not.toContain('R-101')
    expect(voidButtons(wrapper)).toHaveLength(1)
    wrapper.unmount()
  })
})
