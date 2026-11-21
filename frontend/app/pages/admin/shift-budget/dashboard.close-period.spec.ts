import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { defineComponent, reactive, ref } from 'vue'
import Select from 'primevue/select'
import Dashboard from './dashboard.vue'

/**
 * CMP-260909-2152 PR-α 試練108 AC31α（FE・月次締めダイアログの期間選択）。
 *
 * dashboard.vue の月次締めダイアログは、自由入力の YYYY-MM をやめ「終了済みの期間」を選ぶ形にする。
 * - 期間は useShiftBudgetApi の listClosePeriods(orgId, from, to) が返す（GET /close-periods?from&to、from/to は YYYY-MM）。
 * - 選べるのは period_end が今日（Asia/Tokyo）以前（終了日当日を含む）の期間だけ。
 *   CMP-260909-2152 Codex 再検分 P2-4: 終了済みかどうかは BE が業務日（ShiftBudgetBusinessDateProvider）で判定した
 *   ended を正とし、利用者端末のタイムゾーンの「今日」では判定しない。
 *   未終了期間は選択肢に出さない、または disabled（options の disabled=true）にする。
 * - 既定は終了済みの最新期間。確認文言とトーストに期間（YYYY-MM-DD〜YYYY-MM-DD）を出す。
 * - 締め実行は既存どおり executeMonthlyClose({ organization_id, year_month: 期間キー })。
 * - ダイアログ内のセレクトは data-testid=monthly-close-period-select、確認文言は monthly-close-confirm。
 * - 締め日設定画面への導線 data-testid=closing-settings-link（BUDGET_ADMIN のみ、href=/admin/shift-budget/settings）。
 * 実装前は自由入力のままのため red。
 */

const scope = reactive({
  current: { type: 'organization', id: '10' },
  loadFromStorage: vi.fn(),
})
const canManageBudget = ref(true)
const ensureLoaded = vi.fn(async () => {})
const listAllocations = vi.fn()
const listClosePeriods = vi.fn()
const executeMonthlyClose = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyInfo = vi.fn()
const notifyWarn = vi.fn()

mockNuxtImport('useScopeStore', () => () => scope)
const userTimezone = ref('Asia/Tokyo')
mockNuxtImport('useDatetime', () => () => ({ userTimezone }))
mockNuxtImport('useNotification', () => () => ({ error: notifyError, success: notifySuccess, info: notifyInfo, warn: notifyWarn }))
mockNuxtImport('useShiftBudgetApi', () => () => ({
  listAllocations, getConsumptionSummary: vi.fn(), listClosePeriods, executeMonthlyClose,
}))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget, ensureLoaded }),
}))

const InlineDialog = defineComponent({
  props: { visible: Boolean },
  emits: ['update:visible'],
  template: '<div v-if="visible"><slot /><slot name="footer" /></div>',
})

// 今日は 2026-11-17（JST）。17日締め: 2026-11 の期間は 10/18〜11/17（終了日が今日＝選べる）、2026-12 は未終了。
function periods() {
  return {
    items: [
      { period_key: '2026-09', period_start: '2026-08-18', period_end: '2026-09-17', closing_day: 17, ended: true },
      { period_key: '2026-10', period_start: '2026-09-18', period_end: '2026-10-17', closing_day: 17, ended: true },
      { period_key: '2026-11', period_start: '2026-10-18', period_end: '2026-11-17', closing_day: 17, ended: true },
      { period_key: '2026-12', period_start: '2026-11-18', period_end: '2026-12-17', closing_day: 17, ended: false },
    ],
  }
}

function periodSelect(wrapper: VueWrapper) {
  const found = wrapper.findAllComponents(Select).find(c => c.attributes('data-testid') === 'monthly-close-period-select')
  if (!found) throw new Error('monthly-close-period-select が見つからない')
  return found
}

function executeButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find(b => b.text() === '実行')
}

async function openDialog() {
  const wrapper = await mountSuspended(Dashboard, { global: { stubs: { Dialog: InlineDialog } } })
  await flushPromises()
  const button = wrapper.findAll('button').find(b => b.text().includes('月次締め'))
  expect(button, '月次締めボタン').toBeDefined()
  await button!.trigger('click')
  await flushPromises()
  return wrapper
}

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-17T03:00:00Z')) // JST 2026-11-17 12:00
  await useNuxtApp().$i18n.setLocale('ja')
  scope.current = { type: 'organization', id: '10' }
  userTimezone.value = 'Asia/Tokyo'
  canManageBudget.value = true
  listAllocations.mockReset().mockResolvedValue({ items: [], total: 0, page: 0, size: 100 })
  listClosePeriods.mockReset().mockResolvedValue(periods())
  executeMonthlyClose.mockReset().mockResolvedValue({
    year_month: '2026-11', closed_allocations: 1, already_closed_allocations: 0, failed_allocations: 0, closed_consumptions: 5,
    processed_organization_ids: [10], failed_organization_ids: [], already_closed_organization_ids: [],
  })
  notifySuccess.mockReset()
  notifyError.mockReset()
  notifyInfo.mockReset()
  notifyWarn.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('試練108 AC31α 月次締めダイアログ: 期間選択', () => {
  it('試練108 AC31α 自由入力の YYYY-MM をやめ、組織指定で期間一覧を取得してセレクトで選ばせる', async () => {
    const wrapper = await openDialog()
    expect(listClosePeriods).toHaveBeenCalledWith('10', expect.stringMatching(/^\d{4}-\d{2}$/), expect.stringMatching(/^\d{4}-\d{2}$/))
    expect(wrapper.find('input[placeholder="YYYY-MM"]').exists()).toBe(false)
    expect(periodSelect(wrapper).exists()).toBe(true)
  })

  it('試練108 AC31α 選べるのは終了済み（終了日が今日以前）の期間だけ。未終了は出さないか disabled', async () => {
    const wrapper = await openDialog()
    const options = periodSelect(wrapper).props('options') as { value: string, disabled?: boolean }[]
    const selectable = options.filter(o => !o.disabled).map(o => o.value)
    expect(selectable).toEqual(expect.arrayContaining(['2026-09', '2026-10', '2026-11']))
    expect(selectable).not.toContain('2026-12')
  })

  it('試練108 AC31α 既定は終了済みの最新期間（終了日が今日の 2026-11）', async () => {
    const wrapper = await openDialog()
    expect(periodSelect(wrapper).props('modelValue')).toBe('2026-11')
  })

  it('試練108 AC31α 期間表示（label）に開始日と終了日を出す', async () => {
    const wrapper = await openDialog()
    const options = periodSelect(wrapper).props('options') as { value: string, label: string }[]
    const label = options.find(o => o.value === '2026-11')!.label
    expect(label).toContain('2026-10-18')
    expect(label).toContain('2026-11-17')
  })

  it('試練108 AC31α 終了済みの期間が1つも無いときは実行できない', async () => {
    listClosePeriods.mockResolvedValue({
      items: [{ period_key: '2026-12', period_start: '2026-11-18', period_end: '2026-12-17', closing_day: 17, ended: false }],
    })
    const wrapper = await openDialog()
    const exec = executeButton(wrapper)
    expect(exec).toBeDefined()
    expect(exec!.attributes('disabled')).toBeDefined()
    await exec!.trigger('click')
    await flushPromises()
    expect(executeMonthlyClose).not.toHaveBeenCalled()
  })
})

describe('CMP-260909-2152 再検分 P2-4 締め可否は BE の業務日で判定する', () => {
  it('利用者端末のタイムゾーンでは前日（LA 11/16）でも、BE が終了済み（ended=true）とした 2026-11 を選べて既定になる', async () => {
    userTimezone.value = 'America/Los_Angeles' // 2026-11-17T03:00Z は LA で 11/16 19:00
    const wrapper = await openDialog()
    const options = periodSelect(wrapper).props('options') as { value: string, disabled?: boolean }[]
    expect(options.find(o => o.value === '2026-11')?.disabled).toBeFalsy()
    expect(periodSelect(wrapper).props('modelValue')).toBe('2026-11')
  })

  it('利用者端末のタイムゾーンでは翌日（キリティマティ 11/18）でも、BE が未終了（ended=false）とした期間は選べず実行しない', async () => {
    vi.setSystemTime(new Date('2026-11-17T12:00:00Z')) // JST 11/17 21:00、Pacific/Kiritimati（UTC+14）では 11/18 02:00
    userTimezone.value = 'Pacific/Kiritimati'
    listClosePeriods.mockResolvedValue({
      // 18日締め: 2026-11 は 10/19〜11/18。JST の業務日 11/17 では未終了
      items: [{ period_key: '2026-11', period_start: '2026-10-19', period_end: '2026-11-18', closing_day: 18, ended: false }],
    })
    const wrapper = await openDialog()
    const options = periodSelect(wrapper).props('options') as { value: string, disabled?: boolean }[]
    expect(options.find(o => o.value === '2026-11')?.disabled).toBe(true)
    const exec = executeButton(wrapper)
    if (exec) await exec.trigger('click')
    await flushPromises()
    expect(executeMonthlyClose).not.toHaveBeenCalled()
  })
})

describe('試練108 AC31α 月次締めダイアログ: 確認文言・実行・トースト', () => {
  it('試練108 AC31α 確認文言に選んだ期間（開始日と終了日）を出し、選び直すと追随する', async () => {
    const wrapper = await openDialog()
    const confirm = () => wrapper.get('[data-testid="monthly-close-confirm"]').text()
    expect(confirm()).toContain('2026-10-18')
    expect(confirm()).toContain('2026-11-17')
    periodSelect(wrapper).vm.$emit('update:modelValue', '2026-10')
    await flushPromises()
    expect(confirm()).toContain('2026-09-18')
    expect(confirm()).toContain('2026-10-17')
  })

  it('試練108 AC31α 実行は選んだ期間キーを year_month で渡し、成功トーストに期間を出す', async () => {
    const wrapper = await openDialog()
    await executeButton(wrapper)!.trigger('click')
    await flushPromises()
    expect(executeMonthlyClose).toHaveBeenCalledTimes(1)
    expect(executeMonthlyClose).toHaveBeenCalledWith({ organization_id: 10, year_month: '2026-11' })
    expect(notifySuccess).toHaveBeenCalledTimes(1)
    const message = String(notifySuccess.mock.calls[0]![0])
    expect(message).toContain('2026-10-18')
    expect(message).toContain('2026-11-17')
  })

  it('試練108 AC31α 古い期間を選んで実行すると、その期間キーで締める', async () => {
    const wrapper = await openDialog()
    periodSelect(wrapper).vm.$emit('update:modelValue', '2026-10')
    await flushPromises()
    await executeButton(wrapper)!.trigger('click')
    await flushPromises()
    expect(executeMonthlyClose).toHaveBeenCalledWith({ organization_id: 10, year_month: '2026-10' })
  })

  it('試練108 AC31α 未終了期間を選んでも実行しない（409 を待たずに止める）', async () => {
    const wrapper = await openDialog()
    periodSelect(wrapper).vm.$emit('update:modelValue', '2026-12')
    await flushPromises()
    const exec = executeButton(wrapper)
    if (exec) await exec.trigger('click')
    await flushPromises()
    expect(executeMonthlyClose).not.toHaveBeenCalled()
  })
})

describe('試練108 AC31α 月次締め: 権限と導線', () => {
  it('試練108 AC31α BUDGET_ADMIN でなければ月次締めボタンも締め日設定への導線も出さない', async () => {
    canManageBudget.value = false
    const wrapper = await mountSuspended(Dashboard, { global: { stubs: { Dialog: InlineDialog } } })
    await flushPromises()
    expect(wrapper.findAll('button').some(b => b.text().includes('月次締め'))).toBe(false)
    expect(wrapper.find('[data-testid="closing-settings-link"]').exists()).toBe(false)
  })

  it('試練108 AC31α BUDGET_ADMIN には締め日設定画面への導線がある', async () => {
    const wrapper = await mountSuspended(Dashboard, { global: { stubs: { Dialog: InlineDialog } } })
    await flushPromises()
    const link = wrapper.find('[data-testid="closing-settings-link"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/admin/shift-budget/settings')
  })
})

/**
 * CMP-260909-2152 Codex 検分3 P2-1: 段取り役は Post 失敗・ABORTED を HTTP 200 の failed_allocations>0 に集計する。
 * 画面は失敗件数を見て、全件失敗・部分成功・締め済みを区別して通知する。
 * - 全件失敗（closed=0・failed>0）: 成功／情報を出さず、失敗件数と未締めの割当が残る旨をエラー通知し、ダイアログを閉じない（再実行できる）。
 * - 部分成功（closed>0・failed>0）: 成功ではなく警告で、締めた件数・失敗件数・未締めが残る旨を出し、ダイアログを閉じて再読込する。
 * - 締め済み（already>0・closed=0・failed=0）: 情報通知。失敗が混ざれば失敗を優先する。
 */
function closeResult(closed: number, already: number, failed: number) {
  return {
    year_month: '2026-11', closed_allocations: closed, already_closed_allocations: already, failed_allocations: failed,
    closed_consumptions: closed * 3,
    processed_organization_ids: closed > 0 ? [10] : [],
    failed_organization_ids: failed > 0 ? [10] : [],
    already_closed_organization_ids: already > 0 ? [10] : [],
  }
}

async function runClose(result: ReturnType<typeof closeResult>) {
  executeMonthlyClose.mockResolvedValue(result)
  const wrapper = await openDialog()
  await executeButton(wrapper)!.trigger('click')
  await flushPromises()
  return wrapper
}

describe('CMP-260909-2152 Codex 検分3 P2-1 締め結果の失敗件数を通知に反映する', () => {
  it('全件失敗は成功を出さず、失敗件数と未締めが残る旨をエラー通知し、ダイアログを閉じない', async () => {
    const wrapper = await runClose(closeResult(0, 0, 2))
    expect(notifySuccess).not.toHaveBeenCalled()
    expect(notifyInfo).not.toHaveBeenCalled()
    expect(notifyError).toHaveBeenCalledTimes(1)
    const message = String(notifyError.mock.calls[0]![0])
    expect(message).toContain('2026-11-17')
    expect(message).toContain('失敗 2 件')
    expect(message).toContain('未締め')
    expect(wrapper.find('[data-testid="monthly-close-confirm"]').exists()).toBe(true)
  })

  it('部分成功は成功ではなく警告で、締めた件数・失敗件数・未締めが残る旨を出し、ダイアログを閉じる', async () => {
    const wrapper = await runClose(closeResult(1, 0, 1))
    expect(notifySuccess).not.toHaveBeenCalled()
    expect(notifyWarn).toHaveBeenCalledTimes(1)
    const message = String(notifyWarn.mock.calls[0]![0])
    expect(message).toContain('締め 1 件')
    expect(message).toContain('失敗 1 件')
    expect(message).toContain('未締め')
    expect(wrapper.find('[data-testid="monthly-close-confirm"]').exists()).toBe(false)
  })

  it('締め済みと失敗が混在（closed=0・already>0・failed>0）は締め済み扱いにせず失敗として通知する', async () => {
    const wrapper = await runClose(closeResult(0, 3, 1))
    expect(notifyInfo).not.toHaveBeenCalled()
    expect(notifySuccess).not.toHaveBeenCalled()
    expect(notifyError).toHaveBeenCalledTimes(1)
    expect(String(notifyError.mock.calls[0]![0])).toContain('失敗 1 件')
    expect(wrapper.find('[data-testid="monthly-close-confirm"]').exists()).toBe(true)
  })

  it('締め済みのみ（already>0・closed=0・failed=0）は締め済みの情報通知を出し、ダイアログを閉じる', async () => {
    const wrapper = await runClose(closeResult(0, 3, 0))
    expect(notifyInfo).toHaveBeenCalledTimes(1)
    expect(notifySuccess).not.toHaveBeenCalled()
    expect(notifyError).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="monthly-close-confirm"]').exists()).toBe(false)
  })
})
