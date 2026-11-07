import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { UserFavoriteItem } from '~/types/favorite'
import FavoriteQuickEditDialog from './FavoriteQuickEditDialog.vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('#app', () => ({ navigateTo: vi.fn() }))

const getTeam = vi.fn()
const getOrganization = vi.fn()
const updateTeam = vi.fn()
const updateOrganization = vi.fn()
const success = vi.fn()
const handleApiError = vi.fn()
const wrappers: ReturnType<typeof mount>[] = []

function item(
  type: 'TEAM' | 'ORGANIZATION',
  id = '123',
  slug = 'canonical-scope',
): UserFavoriteItem {
  return {
    favoriteId: `fav-${type}-${id}`,
    entityType: type,
    entityId: id,
    displayOrder: 0,
    createdAt: '2026-11-07T00:00:00Z',
    entity: {
      name: '一覧の旧名',
      description: null,
      iconUrl: null,
      pageUrl: `/${type === 'TEAM' ? 'teams' : 'organizations'}/${slug}`,
      status: 'AVAILABLE',
      canEdit: true,
      editableFields: ['name'],
    },
  }
}
function detail(version: number | null | undefined = 0, id = 123, name = '取得した名前') {
  return { data: { numericId: id, basicInfo: { name }, metadata: { version } } }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function open(value: UserFavoriteItem) {
  const wrapper = mount(FavoriteQuickEditDialog, { props: { modelValue: value } })
  wrappers.push(wrapper)
  return wrapper
}
function save(wrapper: ReturnType<typeof open>) {
  return wrapper.findAll('button').find((button) => button.classes().includes('bg-blue-600'))!
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('useTeamApi', () => ({ getTeam, updateTeam }))
  vi.stubGlobal('useOrganizationApi', () => ({ getOrganization, updateOrganization }))
  vi.stubGlobal('useSocialProfileApi', () => ({ updateMyProfile: vi.fn() }))
  vi.stubGlobal('useNotification', () => ({ success }))
  vi.stubGlobal('useErrorHandler', () => ({ handleApiError }))
  getTeam.mockResolvedValue(detail())
  getOrganization.mockResolvedValue(detail())
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.unstubAllGlobals()
})

describe.each(['TEAM', 'ORGANIZATION'] as const)('%s の名称更新契約', (type) => {
  const get = () => (type === 'TEAM' ? getTeam : getOrganization)
  const update = () => (type === 'TEAM' ? updateTeam : updateOrganization)

  it('詳細取得中の名称入力を無効化し、取得後に編集可能にする', async () => {
    const pending = deferred<ReturnType<typeof detail>>()
    get().mockReturnValue(pending.promise)
    const wrapper = open(item(type))
    expect(wrapper.find('input').attributes('disabled')).toBeDefined()
    pending.resolve(detail())
    await flushPromises()
    expect(wrapper.find('input').attributes('disabled')).toBeUndefined()
    await wrapper.find('input').setValue('利用者が編集した名称')
    expect(wrapper.find('input').element.value).toBe('利用者が編集した名称')
  })

  it.each([0, 7])('正準slugと取得時version=%sで名称だけ保存する', async (version) => {
    get().mockResolvedValue(detail(version))
    const wrapper = open(item(type))
    await flushPromises()
    expect(get()).toHaveBeenCalledExactlyOnceWith('canonical-scope')
    expect(wrapper.find('input').element.value).toBe('取得した名前')
    await wrapper.find('input').setValue('  新しい名称  ')
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(update()).toHaveBeenCalledExactlyOnceWith('canonical-scope', {
      name: '新しい名称',
      version,
    })
    expect(get()).toHaveBeenCalledTimes(1)
    expect(success).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('saved')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toEqual([[null]])
  })

  it('取得中は保存できず二重送信もしない', async () => {
    const pending = deferred<ReturnType<typeof detail>>()
    get().mockReturnValue(pending.promise)
    const wrapper = open(item(type))
    expect(save(wrapper).attributes('disabled')).toBeDefined()
    await save(wrapper).trigger('click')
    expect(update()).not.toHaveBeenCalled()
    pending.resolve(detail())
    await flushPromises()
    const saving = deferred<ReturnType<typeof detail>>()
    update().mockReturnValue(saving.promise)
    await save(wrapper).trigger('click')
    await save(wrapper).trigger('click')
    expect(update()).toHaveBeenCalledTimes(1)
    saving.resolve(detail(1))
    await flushPromises()
  })

  it.each([null, undefined])('version=%sでは保存不可・理由を表示', async (version) => {
    get().mockResolvedValue({
      data: { numericId: 123, basicInfo: { name: '名称' }, metadata: { version } },
    })
    const wrapper = open(item(type))
    await flushPromises()
    expect(save(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(update()).not.toHaveBeenCalled()
  })

  it('取得失敗を成功扱いせず保存不可にする', async () => {
    get().mockRejectedValue(new Error('読取失敗'))
    const wrapper = open(item(type))
    await flushPromises()
    expect(save(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(success).not.toHaveBeenCalled()
  })

  it('別numericIdの詳細を保存しない', async () => {
    get().mockResolvedValue(detail(0, 999))
    const wrapper = open(item(type))
    await flushPromises()
    expect(save(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
  })

  it.each([400, 403, 404, 409])(
    'PATCH %sでは入力を保持し成功emit・自動再送しない',
    async (status) => {
      update().mockRejectedValue(Object.assign(new Error('更新失敗'), { statusCode: status }))
      const wrapper = open(item(type))
      await flushPromises()
      await wrapper.find('input').setValue('保持する名称')
      await save(wrapper).trigger('click')
      await flushPromises()
      expect(wrapper.find('input').element.value).toBe('保持する名称')
      expect(wrapper.find('[role="alert"]').exists()).toBe(true)
      expect(wrapper.emitted('saved')).toBeUndefined()
      expect(wrapper.emitted('update:modelValue')).toBeUndefined()
      expect(success).not.toHaveBeenCalled()
      expect(update()).toHaveBeenCalledTimes(1)
    },
  )

  it('A→Bで古い成功応答を採用しない', async () => {
    const a = deferred<ReturnType<typeof detail>>()
    get()
      .mockReturnValueOnce(a.promise)
      .mockResolvedValueOnce(detail(4, 456, 'B名称'))
    const wrapper = open(item(type))
    await wrapper.setProps({ modelValue: item(type, '456', 'scope-b') })
    await flushPromises()
    a.resolve(detail(1))
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('B名称')
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(update()).toHaveBeenCalledWith('scope-b', { name: 'B名称', version: 4 })
  })

  it('A→Bで古い失敗応答がBを保存不可にしない', async () => {
    const a = deferred<ReturnType<typeof detail>>()
    get()
      .mockReturnValueOnce(a.promise)
      .mockResolvedValueOnce(detail(4, 456, 'B名称'))
    const wrapper = open(item(type))
    await wrapper.setProps({ modelValue: item(type, '456', 'scope-b') })
    await flushPromises()
    expect(get()).toHaveBeenCalledTimes(2)
    a.reject(new Error('旧対象の失敗'))
    await flushPromises()
    expect(save(wrapper).attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('close→同じ対象reopenでも旧応答を採用しない', async () => {
    const old = deferred<ReturnType<typeof detail>>()
    get()
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(detail(9, 123, '再表示名称'))
    const selected = item(type)
    const wrapper = open(selected)
    await wrapper.setProps({ modelValue: null })
    await wrapper.setProps({ modelValue: selected })
    await flushPromises()
    old.resolve(detail(1))
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('再表示名称')
  })

  it('保存中の対象切替後は旧成功で新対象を閉じない', async () => {
    const pending = deferred<ReturnType<typeof detail>>()
    update().mockReturnValue(pending.promise)
    const wrapper = open(item(type))
    await flushPromises()
    await save(wrapper).trigger('click')
    get().mockResolvedValueOnce(detail(4, 456, 'B名称'))
    await wrapper.setProps({ modelValue: item(type, '456', 'scope-b') })
    await flushPromises()
    pending.resolve(detail(1))
    await flushPromises()
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(success).not.toHaveBeenCalled()
    expect(wrapper.find('input').element.value).toBe('B名称')
  })

  it('A→B→Aでも最初のA応答を採用しない', async () => {
    const old = deferred<ReturnType<typeof detail>>()
    get()
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(detail(4, 456, 'B名称'))
      .mockResolvedValueOnce(detail(9, 123, '最新A名称'))
    const selected = item(type)
    const wrapper = open(selected)
    await wrapper.setProps({ modelValue: item(type, '456', 'scope-b') })
    await wrapper.setProps({ modelValue: selected })
    await flushPromises()
    old.resolve(detail(1))
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('最新A名称')
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(update()).toHaveBeenCalledWith('canonical-scope', { name: '最新A名称', version: 9 })
  })

  it('旧対象の保存エラーを切替後の対象へ表示しない', async () => {
    const pending = deferred<ReturnType<typeof detail>>()
    update().mockReturnValue(pending.promise)
    const wrapper = open(item(type))
    await flushPromises()
    await save(wrapper).trigger('click')
    get().mockResolvedValueOnce(detail(4, 456, 'B名称'))
    await wrapper.setProps({ modelValue: item(type, '456', 'scope-b') })
    await flushPromises()
    pending.reject(new Error('旧対象の保存失敗'))
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(handleApiError).not.toHaveBeenCalled()
    expect(wrapper.find('input').element.value).toBe('B名称')
    expect(wrapper.emitted('saved')).toBeUndefined()
  })

  it('数字だけの正当slugも利用する', async () => {
    const wrapper = open(item(type, '123', '456'))
    await flushPromises()
    await save(wrapper).trigger('click')
    await flushPromises()
    expect(update()).toHaveBeenCalledWith('456', { name: '取得した名前', version: 0 })
  })

  it.each([
    'https://example.com/teams/abc',
    '/wrong/abc',
    '/teams/abc/more',
    '/teams/abc?x=1',
    '/teams/abc#hash',
  ])('非正準URL %sを更新先にしない', async (pageUrl) => {
    const selected = item(type)
    selected.entity.pageUrl = pageUrl
    const wrapper = open(selected)
    await flushPromises()
    expect(get()).not.toHaveBeenCalled()
    expect(save(wrapper).attributes('disabled')).toBeDefined()
  })
})
