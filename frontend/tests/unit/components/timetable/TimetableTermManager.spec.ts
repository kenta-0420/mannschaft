import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TimetableTermManager from '~/components/timetable/TimetableTermManager.vue'
import type { TimetableTerm } from '~/types/timetable'

// テスト環境の既定ロケールは en のため、文言は en の訳で照合する。
/**
 * CMP-261105-1507 学期の管理部品（チーム・組織で共用）。
 *
 *   TTM-01 管理権限が無ければ追加・削除の操作を出さない
 *   TTM-02 削除は確認を挟む（確認前に API を呼ばない）
 *   TTM-03 時間割が残る学期の削除失敗（409 TIMETABLE_022）は専用文言で出す
 *   TTM-04 開始日が終了日以降なら送信せず、終了日の欄に文言を出す
 *   TTM-05 400 の fieldErrors は該当欄に出す
 *   TTM-06 日付範囲の重複（409 TIMETABLE_021）は汎用トーストでなく専用文言で出す
 *   TTM-07 チームはチームの作成 API、組織は組織の作成 API を呼ぶ
 */

const createTeamTerm = vi.fn()
const createOrgTerm = vi.fn()
const deleteTerm = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()
let pendingAccept: (() => void | Promise<void>) | null = null
const confirmAction = vi.fn((options: { onAccept: () => void | Promise<void> }) => {
  pendingAccept = options.onAccept
})

mockNuxtImport('useTimetableApi', () => () => ({ createTeamTerm, createOrgTerm, deleteTerm }))
mockNuxtImport('useNotification', () => () => ({
  success: notifySuccess,
  error: notifyError,
  warn: vi.fn(),
  info: vi.fn(),
}))
mockNuxtImport('useConfirmDialog', () => () => ({ confirmAction }))

const terms: TimetableTerm[] = [
  {
    id: 11,
    name: '1学期',
    startDate: '2026-04-01',
    endDate: '2026-07-31',
    academicYear: 2026,
    scopeType: 'TEAM',
  },
]

function mountManager(props: Partial<{ scopeType: 'team' | 'organization'; canManage: boolean }> = {}) {
  return mountSuspended(TimetableTermManager, {
    props: {
      scopeType: props.scopeType ?? 'team',
      scopeSlug: 'team-a',
      terms,
      canManage: props.canManage ?? true,
      hint: 'hint',
    },
    attachTo: document.body,
  })
}

async function flush() {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await nextTick()
}

function q<T extends Element = HTMLElement>(sel: string): T | null {
  return document.body.querySelector<T>(sel)
}

async function setInput(sel: string, value: string) {
  const el = q<HTMLInputElement>(sel)
  expect(el, sel).not.toBeNull()
  el!.value = value
  el!.dispatchEvent(new Event('input'))
  await nextTick()
}

async function openDialogAndFill(start: string, end: string) {
  const wrapper = await mountManager()
  await wrapper.find('[data-testid="term-add-button"]').trigger('click')
  await flush()
  await setInput('[data-testid="term-name-input"]', '2学期')
  await setInput('[data-testid="term-start-input"]', start)
  await setInput('[data-testid="term-end-input"]', end)
  return wrapper
}

beforeEach(() => {
  createTeamTerm.mockReset()
  createOrgTerm.mockReset()
  deleteTerm.mockReset()
  notifySuccess.mockReset()
  notifyError.mockReset()
  confirmAction.mockClear()
  pendingAccept = null
  document.body.innerHTML = ''
})

describe('TimetableTermManager', () => {
  it('TTM-01: 管理権限が無ければ追加・削除を出さない', async () => {
    const wrapper = await mountManager({ canManage: false })
    expect(wrapper.find('[data-testid="term-add-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="term-delete-11"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('1学期')
  })

  it('TTM-02: 削除は確認を挟み、承認後にだけ API を呼ぶ', async () => {
    deleteTerm.mockResolvedValue(undefined)
    const wrapper = await mountManager()
    await wrapper.find('[data-testid="term-delete-11"]').trigger('click')
    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(deleteTerm).not.toHaveBeenCalled()

    await pendingAccept!()
    await flush()
    expect(deleteTerm).toHaveBeenCalledWith(11)
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('TTM-03: 時間割が残る学期の削除失敗は専用文言', async () => {
    deleteTerm.mockRejectedValue({ statusCode: 409, data: { error: { code: 'TIMETABLE_022' } } })
    const wrapper = await mountManager()
    await wrapper.find('[data-testid="term-delete-11"]').trigger('click')
    await pendingAccept!()
    await flush()
    expect(notifyError).toHaveBeenCalledWith('This term cannot be deleted because timetables still exist under it. Remove them first')
  })

  it('TTM-04: 開始日が終了日以降なら送信せず終了日の欄に文言を出す', async () => {
    await openDialogAndFill('2026-12-31', '2026-10-01')
    q<HTMLButtonElement>('[data-testid="term-dialog-submit"]')!.click()
    await flush()
    expect(createTeamTerm).not.toHaveBeenCalled()
    expect(q('[data-testid="term-end-date-error"]')?.textContent).toContain('The end date must be after the start date')
  })

  it('TTM-05: 400 の fieldErrors は該当欄に出す', async () => {
    createTeamTerm.mockRejectedValue({
      statusCode: 400,
      data: { error: { code: 'COMMON_001', fieldErrors: [{ field: 'endDate', message: 'サーバ側の文言' }] } },
    })
    await openDialogAndFill('2026-09-01', '2026-12-31')
    q<HTMLButtonElement>('[data-testid="term-dialog-submit"]')!.click()
    await flush()
    expect(createTeamTerm).toHaveBeenCalled()
    expect(q('[data-testid="term-end-date-error"]')?.textContent).toContain('サーバ側の文言')
  })

  it('TTM-06: 日付範囲の重複（409 TIMETABLE_021）は専用文言で出す', async () => {
    createTeamTerm.mockRejectedValue({ statusCode: 409, data: { error: { code: 'TIMETABLE_021' } } })
    await openDialogAndFill('2026-07-01', '2026-12-31')
    q<HTMLButtonElement>('[data-testid="term-dialog-submit"]')!.click()
    await flush()
    const overlap = 'This term overlaps another term. Check the start and end dates'
    expect(q('[data-testid="term-form-error"]')?.textContent).toContain(overlap)
    expect(notifyError).toHaveBeenCalledWith(overlap)
    expect(notifyError).not.toHaveBeenCalledWith('Failed to create term')
  })

  it('TTM-07: チームはチームの作成 API を呼び、成功後に changed を出す', async () => {
    createTeamTerm.mockResolvedValue({ id: 12 })
    const wrapper = await openDialogAndFill('2026-09-01', '2026-12-31')
    q<HTMLButtonElement>('[data-testid="term-dialog-submit"]')!.click()
    await flush()
    expect(createTeamTerm).toHaveBeenCalledWith('team-a', expect.objectContaining({
      name: '2学期',
      startDate: '2026-09-01',
      endDate: '2026-12-31',
    }))
    expect(createOrgTerm).not.toHaveBeenCalled()
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('TTM-08: 年度は桁区切りなしで表示する（「2,026」にしない）', async () => {
    const wrapper = await mountManager()
    await wrapper.find('[data-testid="term-add-button"]').trigger('click')
    await flush()
    const year = q<HTMLInputElement>('[data-testid="term-year-input"]')
    expect(year).not.toBeNull()
    expect(year!.value).toMatch(/^\d{4}$/)
  })

  it('TTM-09: 範囲外の年度（27）は丸めずにエラーとして示し、送信しない', async () => {
    await openDialogAndFill('2026-09-01', '2026-12-31')
    await setInput('[data-testid="term-year-input"]', '27')
    q<HTMLInputElement>('[data-testid="term-year-input"]')!.dispatchEvent(new Event('blur'))
    await flush()
    expect(q<HTMLInputElement>('[data-testid="term-year-input"]')!.value).toBe('27')
    q<HTMLButtonElement>('[data-testid="term-dialog-submit"]')!.click()
    await flush()
    expect(createTeamTerm).not.toHaveBeenCalled()
    expect(q('[data-testid="term-academic-year-error"]')?.textContent).toContain('Enter an academic year between 2000 and 2100')
  })

  it('TTM-10: 全角数字の年度（２０２７）は半角に正規化して送る', async () => {
    createTeamTerm.mockResolvedValue({ id: 14 })
    await openDialogAndFill('2027-04-01', '2027-07-31')
    await setInput('[data-testid="term-year-input"]', '２０２７')
    q<HTMLButtonElement>('[data-testid="term-dialog-submit"]')!.click()
    await flush()
    expect(createTeamTerm).toHaveBeenCalledWith('team-a', expect.objectContaining({ academicYear: 2027 }))
  })

  it('TTM-11: 取得失敗（loadState=error）は空状態ではなく再試行できるエラー表示にし、再試行を親へ伝える', async () => {
    const wrapper = await mountSuspended(TimetableTermManager, {
      props: { scopeType: 'team', scopeSlug: 'team-a', terms: [], canManage: true, hint: 'hint', loadState: 'error' },
    })
    expect(wrapper.find('[data-testid="term-load-error-state"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('No terms defined')
    expect(wrapper.find('[data-testid="term-add-button"]').exists()).toBe(false)
    await wrapper.find('[data-testid="term-load-error-state-retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toBeTruthy()
  })

  it('TTM-07: 組織は組織の作成 API を呼ぶ', async () => {
    createOrgTerm.mockResolvedValue({ id: 13 })
    const wrapper = await mountManager({ scopeType: 'organization' })
    await wrapper.find('[data-testid="term-add-button"]').trigger('click')
    await flush()
    await setInput('[data-testid="term-name-input"]', '後期')
    await setInput('[data-testid="term-start-input"]', '2026-10-01')
    await setInput('[data-testid="term-end-input"]', '2027-03-31')
    q<HTMLButtonElement>('[data-testid="term-dialog-submit"]')!.click()
    await flush()
    expect(createOrgTerm).toHaveBeenCalledWith('team-a', expect.objectContaining({ name: '後期' }))
    expect(createTeamTerm).not.toHaveBeenCalled()
  })
})
