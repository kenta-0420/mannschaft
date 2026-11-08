import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrgDetail } from '~/composables/useOrgDetail'
import type { CityResponse, PrefectureResponse } from '~/types/matching'

// CMP-261108-0922: 実SFCの入力・保存と、制御したAPI応答の順序を検査する。
const mocks = vi.hoisted(() => ({
  update: vi.fn(),
  prefectures: vi.fn(),
  cities: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  handleApiError: vi.fn(),
}))
mockNuxtImport('useOrganizationApi', () => () => ({ updateOrganization: mocks.update }))
mockNuxtImport('useMatchingApi', () => () => ({
  getPrefectures: mocks.prefectures,
  getCities: mocks.cities,
}))
mockNuxtImport('useNotification', () => () => ({
  success: mocks.success,
  error: mocks.error,
  info: mocks.info,
  warn: mocks.warn,
}))
mockNuxtImport('useErrorHandler', () => () => ({
  handleApiError: mocks.handleApiError,
  getFieldErrors: () => ({}),
}))

const Dialog = (await import('./OrgBasicInfoEditDialog.vue')).default
const tokyo = { code: '13', name: '東京都' }
const osaka = { code: '27', name: '大阪府' }
const tokyoCities = [{ code: '13101', name: '千代田区' }]
const osakaCities = [{ code: '27100', name: '大阪市' }]
const master = { data: [tokyo, osaka] }
const textIds = [
  'org-basic-info-name',
  'org-basic-info-name-kana',
  'org-basic-info-nickname1',
  'org-basic-info-nickname2',
]

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function org(
  id = 'org-a',
  name = '元の組織',
  prefecture = tokyo.name,
  city = tokyoCities[0]!.name,
): OrgDetail {
  return {
    id,
    basicInfo: { name, nameKana: 'モト', nickname1: '略称1', nickname2: '略称2' },
    location: { prefecture, city },
    metadata: { version: 0 },
  }
}

async function mountDialog(value = org()) {
  const wrapper = await mountSuspended(Dialog, {
    props: { orgId: value.id, org: value, visible: true },
  })
  await flushPromises()
  return wrapper
}
type DialogWrapper = Awaited<ReturnType<typeof mountDialog>>

function input(wrapper: DialogWrapper, id = 'org-basic-info-name') {
  const field = wrapper
    .findAllComponents(InputText)
    .find((candidate) => candidate.attributes('id') === id)
  if (!field) throw new Error(`入力欄が描画されていません: ${id}`)
  return field
}
function select(wrapper: DialogWrapper, id: string) {
  const field = wrapper
    .findAllComponents(Select)
    .find((candidate) => candidate.attributes('data-testid') === id)
  if (!field) throw new Error(`選択欄が描画されていません: ${id}`)
  return field
}
function save(wrapper: DialogWrapper) {
  const button = wrapper
    .findAllComponents(Button)
    .find((candidate) => candidate.attributes('data-testid') === 'org-basic-info-save')
  if (!button) throw new Error('保存ボタンが描画されていません')
  return button
}
function value(wrapper: DialogWrapper, id = 'org-basic-info-name') {
  return (input(wrapper, id).element as HTMLInputElement).value
}
function saveDisabled(wrapper: DialogWrapper) {
  return (save(wrapper).element as HTMLButtonElement).disabled
}
function expectInputsBlocked(wrapper: DialogWrapper) {
  for (const id of textIds) expect(input(wrapper, id).props('disabled'), id).toBe(true)
  expect(select(wrapper, 'org-basic-info-prefecture').props('disabled')).toBe(true)
  expect(select(wrapper, 'org-basic-info-city').props('disabled')).toBe(true)
  expect(saveDisabled(wrapper)).toBe(true)
}
async function invokeSave(wrapper: DialogWrapper) {
  await (wrapper.vm as unknown as { handleSave: () => Promise<void> }).handleSave()
}

describe('OrgBasicInfoEditDialog 初期化の所有権', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.prefectures.mockResolvedValue(master)
    mocks.cities.mockResolvedValue({ data: tokyoCities })
    mocks.update.mockResolvedValue({ data: {} })
  })
  afterEach(() => vi.restoreAllMocks())

  it('OBI-INIT-01: 都道府県取得中は6項目と保存を操作できない', async () => {
    const pending = deferred<{ data: PrefectureResponse[] }>()
    mocks.prefectures.mockReturnValueOnce(pending.promise)
    const wrapper = await mountDialog()
    try {
      expectInputsBlocked(wrapper)
    } finally {
      pending.resolve(master)
      await flushPromises()
    }
  })

  it('OBI-INIT-02: 既存入力が残る再openでも初期化前の保存handlerはPATCHしない', async () => {
    mocks.prefectures.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountDialog()
    await wrapper.setProps({ visible: false })
    const pending = deferred<{ data: PrefectureResponse[] }>()
    mocks.prefectures.mockReturnValueOnce(pending.promise)
    await wrapper.setProps({ visible: true })
    try {
      await invokeSave(wrapper)
      expect(mocks.update).not.toHaveBeenCalled()
      expect(saveDisabled(wrapper)).toBe(true)
    } finally {
      pending.resolve(master)
      await flushPromises()
    }
  })

  it('OBI-INIT-03: 初期都市取得の完了までは編集と保存を保護する', async () => {
    const pending = deferred<{ data: CityResponse[] }>()
    mocks.cities.mockReturnValue(pending.promise)
    const wrapper = await mountDialog()
    try {
      expectInputsBlocked(wrapper)
    } finally {
      pending.resolve({ data: tokyoCities })
      await flushPromises()
    }
    expect(input(wrapper).props('disabled')).toBe(false)
    expect(value(wrapper)).toBe('元の組織')
  })

  it('OBI-INIT-04: 初期化後の入力をversion0と既存7キーで保存し再表示して元名へ戻せる', async () => {
    const wrapper = await mountDialog()
    expect(input(wrapper).props('disabled')).toBe(false)
    await input(wrapper).setValue('  更新した組織  ')
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith('org-a', {
      name: '更新した組織',
      nameKana: 'モト',
      nickname1: '略称1',
      nickname2: '略称2',
      prefecture: '東京都',
      city: '千代田区',
      version: 0,
    })
    expect(wrapper.emitted('saved')).toHaveLength(1)
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
    const refreshed = org('org-a', '更新した組織')
    refreshed.metadata = { version: 1 }
    await wrapper.setProps({ visible: false, org: refreshed })
    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(value(wrapper)).toBe('更新した組織')
    await input(wrapper).setValue('元の組織')
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.update.mock.calls[1]).toEqual([
      'org-a',
      {
        name: '元の組織',
        nameKana: 'モト',
        nickname1: '略称1',
        nickname2: '略称2',
        prefecture: '東京都',
        city: '千代田区',
        version: 1,
      },
    ])
    expect(wrapper.emitted('saved')).toHaveLength(2)
  })

  it.each(['close', 'unmount'] as const)(
    'OBI-INIT-05: %s後の都道府県応答から都市取得や保存を始めない',
    async (ending) => {
      const pending = deferred<{ data: PrefectureResponse[] }>()
      mocks.prefectures.mockReturnValueOnce(pending.promise)
      const wrapper = await mountDialog()
      if (ending === 'close') await wrapper.setProps({ visible: false })
      else wrapper.unmount()
      pending.resolve(master)
      await flushPromises()
      expect(mocks.cities).not.toHaveBeenCalled()
      expect(mocks.update).not.toHaveBeenCalled()
      expect(wrapper.emitted('saved')).toBeUndefined()
    },
  )

  it('OBI-INIT-06: 再open完了後の入力と候補を旧都道府県応答が上書きしない', async () => {
    const old = deferred<{ data: PrefectureResponse[] }>()
    const current = deferred<{ data: PrefectureResponse[] }>()
    mocks.prefectures.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const wrapper = await mountDialog()
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true, orgId: 'org-b', org: org('org-b', '現在の組織') })
    current.resolve({ data: [tokyo] })
    await flushPromises()
    await input(wrapper).setValue('現在の入力')
    await input(wrapper, 'org-basic-info-name-kana').setValue('ゲンザイ')
    old.resolve({ data: [osaka] })
    await flushPromises()
    expect(value(wrapper)).toBe('現在の入力')
    expect(value(wrapper, 'org-basic-info-name-kana')).toBe('ゲンザイ')
    expect(select(wrapper, 'org-basic-info-prefecture').props('options')).toEqual([tokyo])
    expect(input(wrapper).props('disabled')).toBe(false)
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledWith(
      'org-b',
      expect.objectContaining({ name: '現在の入力', version: 0 }),
    )
  })

  it('OBI-INIT-07: 旧openの失敗でも新openの入力と操作可能状態を維持する', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const old = deferred<{ data: PrefectureResponse[] }>()
    mocks.prefectures.mockReturnValueOnce(old.promise)
    const wrapper = await mountDialog()
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true, org: org('org-a', '再表示の組織') })
    await flushPromises()
    await input(wrapper).setValue('再表示後の入力')
    old.reject(new Error('old prefectures unavailable'))
    await flushPromises()
    expect(value(wrapper)).toBe('再表示後の入力')
    expect(input(wrapper).props('disabled')).toBe(false)
    expect(saveDisabled(wrapper)).toBe(false)
  })

  it('OBI-INIT-08: 旧openの都市応答は新openの候補と入力を上書きしない', async () => {
    const old = deferred<{ data: CityResponse[] }>()
    mocks.cities.mockImplementation((code: string) =>
      code === '13' ? old.promise : Promise.resolve({ data: osakaCities }),
    )
    const wrapper = await mountDialog()
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({
      visible: true,
      org: org('org-a', '大阪の組織', osaka.name, osakaCities[0]!.name),
    })
    await flushPromises()
    await input(wrapper).setValue('大阪での入力')
    old.resolve({ data: tokyoCities })
    await flushPromises()
    expect(select(wrapper, 'org-basic-info-city').props('options')).toEqual(osakaCities)
    expect(value(wrapper)).toBe('大阪での入力')
    expect(input(wrapper).props('disabled')).toBe(false)
  })

  it('OBI-INIT-09: 都道府県を選び直した後の都市候補は最後の選択に対応する', async () => {
    const wrapper = await mountDialog()
    const old = deferred<{ data: CityResponse[] }>()
    const current = deferred<{ data: CityResponse[] }>()
    mocks.cities.mockImplementation((code: string) =>
      code === '27' ? old.promise : current.promise,
    )
    select(wrapper, 'org-basic-info-prefecture').vm.$emit('update:modelValue', osaka)
    await flushPromises()
    select(wrapper, 'org-basic-info-prefecture').vm.$emit('update:modelValue', tokyo)
    await flushPromises()
    current.resolve({ data: tokyoCities })
    await flushPromises()
    old.resolve({ data: osakaCities })
    await flushPromises()
    expect(select(wrapper, 'org-basic-info-city').props('options')).toEqual(tokyoCities)
    expect(select(wrapper, 'org-basic-info-city').props('loading')).toBe(false)
    expect(input(wrapper).props('disabled')).toBe(false)
  })

  it('OBI-INIT-12: 再open時にマスタ未掲載の既存所在地を名称更新で失わない', async () => {
    const wrapper = await mountDialog()
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({
      visible: true,
      orgId: 'org-b',
      org: org('org-b', '別の組織', 'マスタ未掲載の地域', '既存の所在地'),
    })
    await flushPromises()
    await input(wrapper).setValue('名称だけ更新')
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith('org-b', {
      name: '名称だけ更新',
      nameKana: 'モト',
      nickname1: '略称1',
      nickname2: '略称2',
      prefecture: 'マスタ未掲載の地域',
      city: '既存の所在地',
      version: 0,
    })
  })

  it.each(['prefectures', 'cities'] as const)(
    'OBI-INIT-10: %s取得失敗時も既存値のfallbackで名称を保存できる',
    async (failing) => {
      vi.spyOn(console, 'error').mockImplementation(() => {})
      mocks[failing].mockRejectedValueOnce(new Error('master unavailable'))
      const wrapper = await mountDialog()
      expect(input(wrapper).props('disabled')).toBe(false)
      expect(value(wrapper)).toBe('元の組織')
      await input(wrapper).setValue('fallback後の入力')
      await save(wrapper).trigger('click')
      await flushPromises()
      expect(mocks.update).toHaveBeenCalledWith(
        'org-a',
        expect.objectContaining({
          name: 'fallback後の入力',
          prefecture: tokyo.name,
          city: tokyoCities[0]!.name,
          version: 0,
        }),
      )
      expect(console.error).toHaveBeenCalled()
    },
  )

  it('OBI-INIT-11: 保存失敗時は入力を保持し成功emitをせず同じ入力で再試行できる', async () => {
    mocks.update.mockRejectedValueOnce(
      Object.assign(new Error('update conflict'), { statusCode: 409 }),
    )
    const wrapper = await mountDialog()
    await input(wrapper).setValue('保存したい入力')
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(value(wrapper)).toBe('保存したい入力')
    expect(mocks.handleApiError).toHaveBeenCalled()
    expect(saveDisabled(wrapper)).toBe(false)
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.update).toHaveBeenCalledTimes(2)
    expect(mocks.update.mock.calls[1]?.[1]).toEqual(
      expect.objectContaining({ name: '保存したい入力' }),
    )
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })
})
