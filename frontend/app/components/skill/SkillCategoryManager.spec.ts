// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import SkillCategoryManager from './SkillCategoryManager.vue'

/**
 * CMP-261012-0313 F-AC6 / F-AC8: カテゴリ管理。
 * - 作成・改名・削除の「成功後のみ」changed を emit する（親が登録フォーム・一覧・マトリクスを再取得するため）。
 * - 削除は useConfirmDialog().confirmAction を経る。
 */

const api = vi.hoisted(() => ({
  getSkillCategories: vi.fn(),
  createSkillCategory: vi.fn(),
  updateSkillCategory: vi.fn(),
  deleteSkillCategory: vi.fn(),
}))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
const confirm = vi.hoisted(() => ({ confirmAction: vi.fn() }))

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
mockNuxtImport('useSkillApi', () => () => api)
mockNuxtImport('useNotification', () => () => notify)
mockNuxtImport('useConfirmDialog', () => () => confirm)

const Button = defineComponent({
  props: { icon: { type: String, default: '' }, label: { type: String, default: '' } },
  emits: ['click'],
  setup(p, { emit }) {
    return () => h('button', { type: 'button', 'data-icon': p.icon, onClick: () => emit('click') }, p.label)
  },
})
const Dialog = defineComponent({
  props: { visible: { type: Boolean, default: false } },
  setup(p, { slots }) {
    return () => (p.visible ? h('div', { 'data-test': 'dialog' }, [slots.default?.(), slots.footer?.()]) : null)
  },
})
const InputText = defineComponent({
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(p, { emit }) {
    return () => h('input', { value: p.modelValue, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value) })
  },
})
const stubs = { Button, Dialog, InputText, InputNumber: true, ToggleSwitch: true, LoadingBounce: true }

const category = { id: 7, name: '運転', description: null, icon: null, sortOrder: 1, isActive: true, createdAt: '' }

let wrapper: VueWrapper | undefined
async function render() {
  wrapper = mount(SkillCategoryManager, { props: { teamId: 'e2e' }, global: { stubs } })
  await flushPromises()
  return wrapper
}
const btn = (w: VueWrapper, icon: string) => w.find(`button[data-icon="${icon}"]`)

async function submitCreate(w: VueWrapper) {
  await btn(w, 'pi pi-plus').trigger('click')
  await w.find('input').setValue('新カテゴリ')
  await btn(w, 'pi pi-check').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  api.getSkillCategories.mockResolvedValue({ data: [category] })
  api.createSkillCategory.mockResolvedValue({ data: category })
  api.updateSkillCategory.mockResolvedValue({ data: category })
  api.deleteSkillCategory.mockResolvedValue({})
  confirm.confirmAction.mockImplementation((o: { onAccept: () => void }) => o.onAccept())
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('SkillCategoryManager: F-AC6 成功後のみ changed を emit', () => {
  it('初期表示（一覧の読込）では changed を emit しない', async () => {
    const w = await render()
    expect(w.emitted('changed')).toBeUndefined()
  })

  it('作成に成功したら changed を1回 emit する', async () => {
    const w = await render()
    await submitCreate(w)
    expect(api.createSkillCategory).toHaveBeenCalled()
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('作成に失敗したら changed を emit せずエラー通知する', async () => {
    api.createSkillCategory.mockRejectedValue(new Error('500'))
    const w = await render()
    await submitCreate(w)
    expect(w.emitted('changed')).toBeUndefined()
    expect(notify.error).toHaveBeenCalled()
  })

  it('改名（更新）に成功したら changed を emit する', async () => {
    const w = await render()
    await btn(w, 'pi pi-pencil').trigger('click')
    await w.find('input').setValue('運転（改）')
    await btn(w, 'pi pi-check').trigger('click')
    await flushPromises()
    expect(api.updateSkillCategory).toHaveBeenCalledWith('e2e', 7, expect.objectContaining({ name: '運転（改）' }))
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('更新に失敗したら changed を emit しない', async () => {
    api.updateSkillCategory.mockRejectedValue(new Error('409'))
    const w = await render()
    await btn(w, 'pi pi-pencil').trigger('click')
    await btn(w, 'pi pi-check').trigger('click')
    await flushPromises()
    expect(w.emitted('changed')).toBeUndefined()
    expect(notify.error).toHaveBeenCalled()
  })

  it('削除に成功したら changed を emit する', async () => {
    const w = await render()
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(api.deleteSkillCategory).toHaveBeenCalledWith('e2e', 7)
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('削除に失敗したら changed を emit せずエラー通知する', async () => {
    api.deleteSkillCategory.mockRejectedValue(new Error('500'))
    const w = await render()
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(w.emitted('changed')).toBeUndefined()
    expect(notify.error).toHaveBeenCalled()
  })
})

describe('SkillCategoryManager: F-AC8 削除は確認ダイアログを経る', () => {
  it('削除ボタンで confirmAction が呼ばれ、承諾前は API を呼ばない', async () => {
    confirm.confirmAction.mockImplementation(() => {})
    const w = await render()
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(confirm.confirmAction).toHaveBeenCalledTimes(1)
    expect(api.deleteSkillCategory).not.toHaveBeenCalled()
  })

  it('キャンセルなら API を呼ばず changed も emit しない', async () => {
    confirm.confirmAction.mockImplementation((o: { onReject?: () => void }) => o.onReject?.())
    const w = await render()
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(api.deleteSkillCategory).not.toHaveBeenCalled()
    expect(w.emitted('changed')).toBeUndefined()
  })

  it('削除失敗時は行を消さない（一覧を再取得せず、カテゴリが残る）', async () => {
    api.deleteSkillCategory.mockRejectedValue(new Error('500'))
    const w = await render()
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(api.getSkillCategories).toHaveBeenCalledTimes(1)
    expect(w.text()).toContain('運転')
  })
})
