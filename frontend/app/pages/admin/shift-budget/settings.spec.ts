import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { reactive, ref } from 'vue'
import Select from 'primevue/select'
import Settings from './settings.vue'

/**
 * CMP-260909-2152 PR-α 試練108 AC31α（FE・締め日設定画面）。
 *
 * 新ページ `/admin/shift-budget/settings`（pages/admin/shift-budget/settings.vue）の契約:
 * - 締め日は 1〜28 と「末日」（値 0）のセレクト。29〜31 は選べない（data-testid=closing-day-select）。
 * - 適用開始は期間キー（YYYY-MM）のセレクト。選べる最も早い期間はサーバーが返す min_effective_period_key（画面で計算しない。closing-effective-select）。
 * - 理由は必須（closing-reason）。空・空白のみは送信できない（closing-submit）。
 * - 変更前後の期間プレビュー（closing-preview-before / closing-preview-after）に YYYY-MM-DD で期間を出す。
 * - 履歴表（closing-history-row-{revision}、空は closing-history-empty）。
 * - 編集できるのは BUDGET_ADMIN のみ。持たない利用者は参照のみ（セレクト・送信が無効、closing-readonly-notice）。
 * - API は useShiftBudgetApi の getClosingSettings(orgId) / changeClosingSetting(orgId, request)
 *   （X-Organization-Id 方式のため組織IDは第1引数、ボディに organization_id を入れない）。
 * - 409 等の失敗は closing-settings-error に表示し、成功トーストを出さない。
 * 実装前はページが無いため red。
 */

const scope = reactive({
  current: { type: 'organization', id: '10' } as { type: string, id: string },
  loadFromStorage: vi.fn(),
})
const canManageBudget = ref(true)
const ensureLoaded = vi.fn(async () => {})
const getClosingSettings = vi.fn()
const changeClosingSetting = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()

mockNuxtImport('useScopeStore', () => () => scope)
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('useNotification', () => () => ({ error: notifyError, success: notifySuccess, info: vi.fn() }))
mockNuxtImport('useShiftBudgetApi', () => () => ({
  getClosingSettings,
  changeClosingSetting,
}))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget, ensureLoaded }),
}))

function settings(overrides: Record<string, unknown> = {}) {
  return {
    closing_day: 0,
    revision: 2,
    min_effective_period_key: '2026-11',
    changes: [
      { revision: 2, effective_period_key: '2026-09', closing_day: 0, previous_closing_day: 15,
        reason: '月末締めへ戻す', actor_user_id: 6, created_at: '2026-08-20T01:00:00Z' },
      { revision: 1, effective_period_key: '2026-04', closing_day: 15, previous_closing_day: 0,
        reason: '給与計算に合わせる', actor_user_id: 6, created_at: '2026-03-20T01:00:00Z' },
    ],
    ...overrides,
  }
}

function selectOf(wrapper: VueWrapper, testid: string) {
  const found = wrapper.findAllComponents(Select).find(c => c.attributes('data-testid') === testid)
  if (!found) throw new Error(`Select ${testid} が見つからない`)
  return found
}

async function mountPage() {
  const wrapper = await mountSuspended(Settings)
  await flushPromises()
  return wrapper
}

async function fillForm(wrapper: VueWrapper, day: number, effective: string, reason: string) {
  selectOf(wrapper, 'closing-day-select').vm.$emit('update:modelValue', day)
  selectOf(wrapper, 'closing-effective-select').vm.$emit('update:modelValue', effective)
  await wrapper.get('[data-testid="closing-reason"]').setValue(reason)
  await flushPromises()
}

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-17T03:00:00Z')) // JST 2026-11-17 12:00
  await useNuxtApp().$i18n.setLocale('ja')
  scope.current = { type: 'organization', id: '10' }
  canManageBudget.value = true
  getClosingSettings.mockReset().mockResolvedValue(settings())
  changeClosingSetting.mockReset().mockResolvedValue(settings({ closing_day: 15, revision: 3 }))
  notifySuccess.mockReset()
  notifyError.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('試練108 AC31α 締め日設定画面: 表示', () => {
  it('試練108 AC31α 組織スコープで締め日設定を組織ID指定で取得し、現在の締め日と履歴を表示する', async () => {
    const wrapper = await mountPage()
    expect(getClosingSettings).toHaveBeenCalledWith('10')
    expect(wrapper.find('[data-testid="closing-current"]').text()).toContain('末日')
    const row2 = wrapper.find('[data-testid="closing-history-row-2"]')
    const row1 = wrapper.find('[data-testid="closing-history-row-1"]')
    expect(row2.exists()).toBe(true)
    expect(row1.exists()).toBe(true)
    expect(row2.text()).toContain('2026-09')
    expect(row2.text()).toContain('月末締めへ戻す')
    expect(row1.text()).toContain('2026-04')
    expect(row1.text()).toContain('15')
    expect(row1.text()).toContain('給与計算に合わせる')
    expect(wrapper.find('[data-testid="closing-history-empty"]').exists()).toBe(false)
  })

  it('試練108 AC31α 履歴が無いときは空の表示を出し、取得失敗とは区別する', async () => {
    getClosingSettings.mockResolvedValue(settings({ revision: 0, changes: [] }))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="closing-history-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="closing-settings-load-error"]').exists()).toBe(false)
  })

  it('試練108 AC31α 取得失敗を既定値（末日）として表示しない', async () => {
    getClosingSettings.mockRejectedValue(new Error('load failed'))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="closing-settings-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="closing-current"]').exists()).toBe(false)
  })

  it('試練108 AC31α 組織スコープでなければ取得せず組織選択を促す', async () => {
    scope.current = { type: 'personal', id: '1' }
    const wrapper = await mountPage()
    expect(getClosingSettings).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="closing-day-select"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('組織スコープ')
  })
})

describe('試練108 AC31α 締め日設定画面: 入力', () => {
  it('試練108 AC31α 締め日は 1〜28 と末日（値0）だけで、29〜31 は選べない', async () => {
    const wrapper = await mountPage()
    const options = selectOf(wrapper, 'closing-day-select').props('options') as { value: number }[]
    const values = options.map(o => o.value)
    expect(values).toHaveLength(29)
    expect(values).toEqual(expect.arrayContaining([...Array.from({ length: 28 }, (_, i) => i + 1), 0]))
    for (const bad of [29, 30, 31, -1]) expect(values).not.toContain(bad)
  })

  it('試練108 AC31α 適用開始は今日（JST）を含む期間以降だけを昇順で出す', async () => {
    const wrapper = await mountPage()
    const keys = (selectOf(wrapper, 'closing-effective-select').props('options') as { value: string }[])
      .map(o => o.value)
    expect(keys.length).toBeGreaterThan(1)
    expect(keys[0]).toBe('2026-11') // 末日締め・今日 11/17 → 2026-11
    expect(keys).toEqual([...keys].sort())
    expect(keys).not.toContain('2026-10')
  })

  it('試練108 AC31α 現在が15日締めなら今日(11/17)を含む期間は 2026-12 から', async () => {
    getClosingSettings.mockResolvedValue(settings({ closing_day: 15, revision: 3, min_effective_period_key: '2026-12' }))
    const wrapper = await mountPage()
    const keys = (selectOf(wrapper, 'closing-effective-select').props('options') as { value: string }[])
      .map(o => o.value)
    expect(keys[0]).toBe('2026-12')
  })

  it('試練108 AC31α 理由が空・空白のみなら送信できず API を呼ばない', async () => {
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '')
    const submit = wrapper.get('[data-testid="closing-submit"]')
    expect(submit.attributes('disabled')).toBeDefined()
    await submit.trigger('click')
    await wrapper.get('[data-testid="closing-reason"]').setValue('   ')
    await flushPromises()
    expect(wrapper.get('[data-testid="closing-submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(changeClosingSetting).not.toHaveBeenCalled()
  })

  it('試練108 AC31α 変更前後の期間プレビューを出す（末日→15日、2026-12 適用）', async () => {
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '給与計算に合わせる')
    const before = wrapper.get('[data-testid="closing-preview-before"]').text()
    const after = wrapper.get('[data-testid="closing-preview-after"]').text()
    expect(before).toContain('2026-12-01')
    expect(before).toContain('2026-12-31')
    expect(before).not.toContain('2026-12-15')
    expect(after).toContain('2026-12-01')
    expect(after).toContain('2026-12-15')
    expect(after).not.toContain('2026-12-31')
  })
})

describe('試練108 AC31α 締め日設定画面: 予約済みの将来変更があるときのプレビュー', () => {
  it('現在は末日締め・12月から15日締めが予約済みで、1月から20日締めへ変更するプレビューは 12/16〜1/20（前月 12月の締め日は予約済みの15日）', async () => {
    getClosingSettings.mockResolvedValue(settings({
      closing_day: 0,
      revision: 1,
      min_effective_period_key: '2026-12',
      changes: [
        { revision: 1, effective_period_key: '2026-12', closing_day: 15, previous_closing_day: 0,
          reason: '12月から15日締め', actor_user_id: 6, created_at: '2026-11-10T01:00:00Z' },
      ],
    }))
    const wrapper = await mountPage()
    await fillForm(wrapper, 20, '2027-01', '20日締めへ')
    const before = wrapper.get('[data-testid="closing-preview-before"]').text()
    const after = wrapper.get('[data-testid="closing-preview-after"]').text()
    expect(before).toContain('2026-12-16')
    expect(before).toContain('2027-01-15')
    expect(after).toContain('2026-12-16')
    expect(after).toContain('2027-01-20')
    expect(after).not.toContain('2027-01-15')
  })
})

describe('試練108 AC31α 締め日設定画面: 送信', () => {
  it('試練108 AC31α 送信は expected_revision・closing_day・effective_period_key・reason だけを組織ID指定で渡す', async () => {
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '給与計算に合わせる')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(changeClosingSetting).toHaveBeenCalledTimes(1)
    expect(changeClosingSetting).toHaveBeenCalledWith('10', {
      expected_revision: 2,
      closing_day: 15,
      effective_period_key: '2026-12',
      reason: '給与計算に合わせる',
    })
  })

  it('試練108 AC31α 末日を選ぶと closing_day は 0 で送る', async () => {
    getClosingSettings.mockResolvedValue(settings({ closing_day: 15, revision: 3 }))
    const wrapper = await mountPage()
    await fillForm(wrapper, 0, '2026-12', '月末締めへ戻す')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(changeClosingSetting).toHaveBeenCalledWith('10', expect.objectContaining({
      expected_revision: 3, closing_day: 0, effective_period_key: '2026-12',
    }))
  })

  it('試練108 AC31α 成功すると成功を通知し、最新の設定を再取得して理由を空に戻す', async () => {
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '給与計算に合わせる')
    getClosingSettings.mockResolvedValue(settings({ closing_day: 0, revision: 3 }))
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(notifySuccess).toHaveBeenCalledTimes(1)
    expect(getClosingSettings).toHaveBeenCalledTimes(2)
    expect((wrapper.get('[data-testid="closing-reason"]').element as HTMLTextAreaElement).value).toBe('')
  })

  it('試練108 AC31α 409（変更の受理条件違反）はエラーを画面に出し、成功を通知せず入力を残す', async () => {
    changeClosingSetting.mockRejectedValue(Object.assign(new Error('conflict'), {
      status: 409, statusCode: 409,
      data: { error: { code: 'SHIFT_BUDGET_038', message: 'CLOSING_DAY_CHANGE_CONFLICT' } },
    }))
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '給与計算に合わせる')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    const error = wrapper.find('[data-testid="closing-settings-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).not.toBe('')
    expect(notifySuccess).not.toHaveBeenCalled()
    expect((wrapper.get('[data-testid="closing-reason"]').element as HTMLTextAreaElement).value)
      .toBe('給与計算に合わせる')
  })

  it('試練108 AC31α 二重クリックでも変更 API は1回だけ', async () => {
    let finish!: (v: unknown) => void
    changeClosingSetting.mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '給与計算に合わせる')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(changeClosingSetting).toHaveBeenCalledTimes(1)
    finish(settings({ closing_day: 15, revision: 3 }))
    await flushPromises()
  })
})

describe('試練108 AC31α 締め日設定画面: 権限', () => {
  it('試練108 AC31α BUDGET_ADMIN でなければ参照のみ（セレクト・理由・送信が無効で API を呼べない）', async () => {
    canManageBudget.value = false
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="closing-current"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="closing-history-row-2"]').exists()).toBe(true)
    const submit = wrapper.find('[data-testid="closing-submit"]')
    if (submit.exists()) {
      expect(submit.attributes('disabled')).toBeDefined()
      await submit.trigger('click')
    }
    for (const id of ['closing-day-select', 'closing-effective-select']) {
      const sel = wrapper.findAllComponents(Select).find(c => c.attributes('data-testid') === id)
      if (sel) expect(sel.props('disabled')).toBe(true)
    }
    const reason = wrapper.find('[data-testid="closing-reason"]')
    if (reason.exists()) expect(reason.attributes('disabled')).toBeDefined()
    await flushPromises()
    expect(changeClosingSetting).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="closing-readonly-notice"]').exists()).toBe(true)
  })

  it('試練108 AC31α 権限を失った時点で送信不能になる（権限の動的な喪失）', async () => {
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '給与計算に合わせる')
    canManageBudget.value = false
    await flushPromises()
    const submit = wrapper.find('[data-testid="closing-submit"]')
    if (submit.exists()) await submit.trigger('click')
    await flushPromises()
    expect(changeClosingSetting).not.toHaveBeenCalled()
  })
})

/**
 * CMP-260909-2152 Codex 検分3 P2-2: 組織切替時に旧組織の設定を破棄し、取得時の組織とリクエスト世代が現在も有効な応答だけ反映する。
 * 旧組織の応答が後着して新組織の画面（締め日・履歴・revision）を上書きし、旧 revision で新組織へ送信する事故を防ぐ。
 */
describe('CMP-260909-2152 Codex 検分3 P2-2 組織切替時に旧組織の応答を反映しない', () => {
  function settingsB() {
    return settings({
      closing_day: 15,
      revision: 7,
      changes: [
        { revision: 7, effective_period_key: '2026-10', closing_day: 15, previous_closing_day: 0,
          reason: '組織Bの変更', actor_user_id: 8, created_at: '2026-09-20T01:00:00Z' },
      ],
    })
  }

  it('組織A取得中に組織Bへ切り替え、Aの応答が後着しても B の締め日・履歴・revision で表示・送信する', async () => {
    let finishA!: (v: unknown) => void
    getClosingSettings.mockImplementation((orgId: string) => orgId === '10'
      ? new Promise((resolve) => { finishA = resolve })
      : Promise.resolve(settingsB()))
    const wrapper = await mountSuspended(Settings)
    expect(getClosingSettings).toHaveBeenCalledWith('10')
    scope.current = { type: 'organization', id: '20' }
    await flushPromises()
    expect(getClosingSettings).toHaveBeenCalledWith('20')
    finishA(settings())
    await flushPromises()
    expect(wrapper.find('[data-testid="closing-history-row-7"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="closing-history-row-2"]').exists()).toBe(false)
    await fillForm(wrapper, 20, '2026-12', '組織Bを20日締めへ')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(changeClosingSetting).toHaveBeenCalledTimes(1)
    expect(changeClosingSetting).toHaveBeenCalledWith('20', expect.objectContaining({ expected_revision: 7 }))
  })

  it('組織A取得中に組織Bへ切り替え、Aの失敗が後着しても B の画面に取得失敗を出さない', async () => {
    let failA!: (e: unknown) => void
    getClosingSettings.mockImplementation((orgId: string) => orgId === '10'
      ? new Promise((_, reject) => { failA = reject })
      : Promise.resolve(settingsB()))
    const wrapper = await mountSuspended(Settings)
    scope.current = { type: 'organization', id: '20' }
    await flushPromises()
    failA(new Error('A failed'))
    await flushPromises()
    expect(wrapper.find('[data-testid="closing-settings-load-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="closing-history-row-7"]').exists()).toBe(true)
  })

  it('組織を切り替えた直後（B 取得中）は A の設定を表示せず、A の revision で送信できない', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="closing-history-row-2"]').exists()).toBe(true)
    getClosingSettings.mockReturnValue(new Promise(() => {}))
    scope.current = { type: 'organization', id: '20' }
    await flushPromises()
    expect(wrapper.find('[data-testid="closing-history-row-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="closing-current"]').exists()).toBe(false)
    const submit = wrapper.find('[data-testid="closing-submit"]')
    if (submit.exists()) await submit.trigger('click')
    await flushPromises()
    expect(changeClosingSetting).not.toHaveBeenCalled()
  })
})

/**
 * CMP-260909-2152 α 実機E2E 不備の根治（revision 競合後の再取得・理由の文字数制限・403 の文言）。
 * 設計書に 409 後の入力の扱いの定めは無いため、入力は保持し、現在値・履歴・revision だけを最新化する。
 */
describe('CMP-260909-2152 α 実機E2E 不備: 409・400・403', () => {
  function conflict409() {
    return Object.assign(new Error('conflict'), { status: 409, statusCode: 409 })
  }

  it('409 のあと最新の設定を再取得して現在値・履歴・revision を更新し、入力は保持する', async () => {
    changeClosingSetting.mockRejectedValueOnce(conflict409())
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '給与計算に合わせる')
    getClosingSettings.mockResolvedValue(settings({
      closing_day: 20,
      revision: 3,
      changes: [
        { revision: 3, effective_period_key: '2026-11', closing_day: 20, previous_closing_day: 0,
          reason: '他の管理者の変更', actor_user_id: 9, created_at: '2026-11-16T01:00:00Z' },
      ],
    }))
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(getClosingSettings).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="closing-settings-error"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="closing-current"]').text()).toContain('20')
    expect(wrapper.find('[data-testid="closing-history-row-3"]').exists()).toBe(true)
    expect((wrapper.get('[data-testid="closing-reason"]').element as HTMLTextAreaElement).value)
      .toBe('給与計算に合わせる')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(changeClosingSetting).toHaveBeenLastCalledWith('10', expect.objectContaining({
      expected_revision: 3, closing_day: 15, effective_period_key: '2026-12',
    }))
  })

  it('409 後の再取得で適用開始の下限が進み、選んだ期間が選べなくなったら選択を外す', async () => {
    changeClosingSetting.mockRejectedValueOnce(conflict409())
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-11', '理由')
    getClosingSettings.mockResolvedValue(settings({ revision: 3, min_effective_period_key: '2026-12' }))
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="closing-submit"]').attributes('disabled')).toBeDefined()
  })

  it('理由の入力欄は 500 字までで、文字数を表示する', async () => {
    const wrapper = await mountPage()
    expect(wrapper.get('[data-testid="closing-reason"]').attributes('maxlength')).toBe('500')
    await wrapper.get('[data-testid="closing-reason"]').setValue('あいう')
    expect(wrapper.get('[data-testid="closing-reason-count"]').text()).toContain('3')
    expect(wrapper.get('[data-testid="closing-reason-count"]').text()).toContain('500')
  })

  it('400 の fieldErrors があれば項目別に表示する', async () => {
    changeClosingSetting.mockRejectedValueOnce(Object.assign(new Error('bad'), {
      status: 400, statusCode: 400,
      data: { error: { code: 'COMMON_001', fieldErrors: [{ field: 'reason', message: '500文字以内で入力してください' }] } },
    }))
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '理由')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="closing-field-error-reason"]').text()).not.toBe('')
    expect(getClosingSettings).toHaveBeenCalledTimes(1)
  })

  it('非 ja ロケールで 400 fieldErrors を受けても、サーバーの日本語メッセージをそのまま表示しない', async () => {
    await useNuxtApp().$i18n.setLocale('en')
    changeClosingSetting.mockRejectedValueOnce(Object.assign(new Error('bad'), {
      status: 400, statusCode: 400,
      data: { error: { code: 'COMMON_001', fieldErrors: [
        { field: 'reason', message: '500文字以内で入力してください' },
        { field: 'closing_day', message: '不正な値です' },
      ] } },
    }))
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', 'reason')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    const text = wrapper.get('[data-testid="closing-field-error-reason"]').text()
      + (wrapper.find('[data-testid="closing-field-errors"]').exists()
        ? wrapper.get('[data-testid="closing-field-errors"]').text() : '')
    expect(text).not.toBe('')
    expect(text).not.toMatch(/[ぁ-んァ-ヶ一-龠]/)
  })

  it('取得が 403 のときは取得失敗ではなく権限不足と分かる文言を出す', async () => {
    getClosingSettings.mockRejectedValue(Object.assign(new Error('forbidden'), { status: 403, statusCode: 403 }))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="closing-settings-forbidden"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="closing-settings-load-error"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('現在のアカウントでは表示できない画面です')
  })
})

/**
 * CMP-260909-2152 α Codex 全体検分 P2-3: 変更の送信中に組織を切り替えたら、旧組織の応答（成功・失敗・409 後の再取得）で
 * 新組織のフォーム・エラーを書き換えない。送信時の組織と組織の世代が現在の画面と一致するときだけ反映する。
 */
describe('CMP-260909-2152 α 検分 P2-3 送信中の組織切替で旧組織の応答を新組織に反映しない', () => {
  function settingsB() {
    return settings({
      closing_day: 15,
      revision: 7,
      changes: [
        { revision: 7, effective_period_key: '2026-10', closing_day: 15, previous_closing_day: 0,
          reason: '組織Bの変更', actor_user_id: 8, created_at: '2026-09-20T01:00:00Z' },
      ],
    })
  }

  async function startSubmitThenSwitch() {
    let finishA!: (v: unknown) => void
    let failA!: (e: unknown) => void
    changeClosingSetting.mockReturnValueOnce(new Promise((resolve, reject) => {
      finishA = resolve
      failA = reject
    }))
    getClosingSettings.mockImplementation((orgId: string) =>
      Promise.resolve(orgId === '20' ? settingsB() : settings()))
    const wrapper = await mountPage()
    await fillForm(wrapper, 15, '2026-12', '組織Aの理由')
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(changeClosingSetting).toHaveBeenCalledWith('10', expect.anything())
    scope.current = { type: 'organization', id: '20' }
    await flushPromises()
    expect(wrapper.find('[data-testid="closing-history-row-7"]').exists()).toBe(true)
    await fillForm(wrapper, 20, '2026-12', '組織Bの理由')
    const callsBefore = getClosingSettings.mock.calls.length
    return { wrapper, finishA, failA, callsBefore }
  }

  function reasonOf(wrapper: VueWrapper) {
    return (wrapper.get('[data-testid="closing-reason"]').element as HTMLTextAreaElement).value
  }

  it('A の成功が後着しても B の入力を消さず、A の再取得もしない', async () => {
    const { wrapper, finishA, callsBefore } = await startSubmitThenSwitch()
    finishA(settings({ closing_day: 15, revision: 3 }))
    await flushPromises()
    expect(reasonOf(wrapper)).toBe('組織Bの理由')
    expect(getClosingSettings.mock.calls.length).toBe(callsBefore)
    expect(wrapper.find('[data-testid="closing-history-row-7"]').exists()).toBe(true)
  })

  it('A の失敗（500）が後着しても B の画面にエラーを出さない', async () => {
    const { wrapper, failA } = await startSubmitThenSwitch()
    failA(Object.assign(new Error('server'), { status: 500, statusCode: 500 }))
    await flushPromises()
    expect(wrapper.find('[data-testid="closing-settings-error"]').exists()).toBe(false)
    expect(reasonOf(wrapper)).toBe('組織Bの理由')
  })

  it('A の 409 が後着しても B のエラー表示・再取得・入力の補正をしない', async () => {
    const { wrapper, failA, callsBefore } = await startSubmitThenSwitch()
    failA(Object.assign(new Error('conflict'), { status: 409, statusCode: 409 }))
    await flushPromises()
    expect(wrapper.find('[data-testid="closing-settings-error"]').exists()).toBe(false)
    expect(getClosingSettings.mock.calls.length).toBe(callsBefore)
    expect(reasonOf(wrapper)).toBe('組織Bの理由')
  })

  it('A の送信中でも B では送信でき、B へ B の revision で送る', async () => {
    const { wrapper, finishA } = await startSubmitThenSwitch()
    changeClosingSetting.mockResolvedValueOnce(settingsB())
    expect(wrapper.get('[data-testid="closing-submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-testid="closing-submit"]').trigger('click')
    await flushPromises()
    expect(changeClosingSetting).toHaveBeenLastCalledWith('20', expect.objectContaining({ expected_revision: 7 }))
    finishA(settings({ closing_day: 15, revision: 3 }))
    await flushPromises()
  })
})
