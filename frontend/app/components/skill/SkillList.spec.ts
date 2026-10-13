// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import SkillList from './SkillList.vue'

const api = vi.hoisted(() => ({
  searchSkills: vi.fn(),
  getSkill: vi.fn(),
  deleteSkill: vi.fn(),
  verifySkill: vi.fn(),
  getCertificateUrl: vi.fn(),
  getSkillCategories: vi.fn(),
  getMySkills: vi.fn(),
}))
const confirm = vi.hoisted(() => ({ confirmAction: vi.fn() }))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
const auth = vi.hoisted(() => ({ currentUser: { id: 10 } as { id: number } | null }))

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
mockNuxtImport('useSkillApi', () => () => api)
mockNuxtImport('useNotification', () => () => notify)
mockNuxtImport('useConfirmDialog', () => () => confirm)
mockNuxtImport('useDatetime', () => () => ({ formatDate: (v: string) => v }))
mockNuxtImport('useAuthStore', () => () => auth)

// 検索API（SkillSearchService）が返す実際の形。name / id / categoryName / version は無い。
const searchItem = (over: Record<string, unknown> = {}) => ({
  memberSkillId: 501,
  userId: 10,
  displayName: '山田',
  categoryId: 7,
  skillName: '普通自動車免許',
  issuer: '公安委員会',
  acquiredOn: '2026-01-01',
  expiresAt: '2030-01-01',
  status: 'PENDING_REVIEW',
  hasCertificate: true,
  verifiedAt: null,
  expiringSoon: false,
  ...over,
})

const fullSkill = {
  id: 501, skillCategoryId: 7, categoryName: '運転', userId: 10, scopeType: 'TEAM', scopeId: 1,
  name: '普通自動車免許', issuer: '公安委員会', credentialNumber: 'No.123', acquiredOn: '2026-01-01',
  expiresAt: '2030-01-01', status: 'PENDING_REVIEW', hasCertificate: false, verifiedAt: null,
  verifiedBy: null, version: 3, createdAt: '2026-01-01T00:00:00',
}

const Button = defineComponent({
  props: { icon: { type: String, default: '' }, label: { type: String, default: '' } },
  emits: ['click'],
  setup(p, { emit }) {
    return () => h('button', { type: 'button', 'data-icon': p.icon, onClick: () => emit('click') }, p.label)
  },
})
const Select = defineComponent({
  props: { options: { type: Array, default: () => [] }, placeholder: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(p, { emit }) {
    return () =>
      h(
        'div',
        { 'data-ph': p.placeholder },
        (p.options as { label: string; value: unknown }[]).map((o) =>
          h('button', { type: 'button', 'data-v': String(o.value), onClick: () => emit('update:modelValue', o.value) }, o.label),
        ),
      )
  },
})
const stubs = { Button, InputText: true, Select, LoadingBounce: true }

let wrapper: VueWrapper | undefined
async function render(canManage = false, scope: 'all' | 'mine' = 'all') {
  wrapper = mount(SkillList, {
    props: { teamId: 'e2e', canManage, scope },
    global: { stubs, directives: { tooltip: {} } },
  })
  await flushPromises()
  return wrapper
}
const btn = (w: VueWrapper, icon: string) => w.find(`button[data-icon="${icon}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  auth.currentUser = { id: 10 }
  api.searchSkills.mockResolvedValue({ data: [searchItem()] })
  api.getSkillCategories.mockResolvedValue({ data: [{ id: 7, name: '運転', description: null, icon: null, sortOrder: 1, isActive: true, createdAt: '' }] })
  api.getSkill.mockResolvedValue({ data: fullSkill })
  api.deleteSkill.mockResolvedValue({})
  api.verifySkill.mockResolvedValue({ data: fullSkill })
  api.getMySkills.mockResolvedValue({ data: [fullSkill] })
  confirm.confirmAction.mockImplementation((o: { onAccept: () => void }) => o.onAccept())
  api.getCertificateUrl.mockResolvedValue({ data: 'https://example.invalid/c.pdf' })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('SkillList: 検索APIの形（memberSkillId / skillName / categoryId）で描画する', () => {
  it('資格名とカテゴリ名が表示される', async () => {
    const w = await render(true)
    expect(w.text()).toContain('普通自動車免許')
    expect(w.text()).toContain('運転')
  })

  it('削除は memberSkillId で呼ばれる', async () => {
    const w = await render(true)
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(api.deleteSkill).toHaveBeenCalledWith('e2e', 501)
  })

  it('承認は memberSkillId で呼ばれる', async () => {
    const w = await render(true)
    await btn(w, 'pi pi-check').trigger('click')
    await flushPromises()
    expect(api.verifySkill).toHaveBeenCalledWith('e2e', 501)
  })

  it('証明書は memberSkillId で呼ばれる', async () => {
    vi.stubGlobal('open', vi.fn())
    const w = await render(true)
    await btn(w, 'pi pi-download').trigger('click')
    await flushPromises()
    expect(api.getCertificateUrl).toHaveBeenCalledWith('e2e', 501)
    vi.unstubAllGlobals()
  })

  it('証明書URLは実API形（data が URL 文字列）から読み、その URL で window.open する', async () => {
    const open = vi.fn()
    vi.stubGlobal('open', open)
    api.getCertificateUrl.mockResolvedValue({ data: 'https://example.invalid/real.pdf?sig=1' })
    const w = await render(true)
    await btn(w, 'pi pi-download').trigger('click')
    await flushPromises()
    expect(open).toHaveBeenCalledWith('https://example.invalid/real.pdf?sig=1', '_blank')
    expect(notify.error).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('編集は個別取得の完全な資格で emit される', async () => {
    const w = await render(true)
    await btn(w, 'pi pi-pencil').trigger('click')
    await flushPromises()
    expect(api.getSkill).toHaveBeenCalledWith('e2e', 501)
    expect(w.emitted('edit')?.[0]).toEqual([fullSkill])
  })

  it('個別取得に失敗したら握りつぶさずエラー通知し emit しない', async () => {
    api.getSkill.mockRejectedValue(new Error('403'))
    const w = await render(true)
    await btn(w, 'pi pi-pencil').trigger('click')
    await flushPromises()
    expect(notify.error).toHaveBeenCalled()
    expect(w.emitted('edit')).toBeUndefined()
  })
})

describe('SkillList: 操作ボタンの表示条件（BE: 本人または ADMIN/DEPUTY のみ）', () => {
  it('本人でなく canManage=false の一般メンバーには編集・削除・証明書が出ない', async () => {
    auth.currentUser = { id: 99 }
    const w = await render(false)
    expect(btn(w, 'pi pi-pencil').exists()).toBe(false)
    expect(btn(w, 'pi pi-trash').exists()).toBe(false)
    expect(btn(w, 'pi pi-download').exists()).toBe(false)
    expect(btn(w, 'pi pi-check').exists()).toBe(false)
  })

  it('本人には canManage=false でも編集・削除が出る（承認は出ない）', async () => {
    const w = await render(false)
    expect(btn(w, 'pi pi-pencil').exists()).toBe(true)
    expect(btn(w, 'pi pi-trash').exists()).toBe(true)
    expect(btn(w, 'pi pi-check').exists()).toBe(false)
  })

  it('管理者(canManage)は他人の資格も編集できる', async () => {
    auth.currentUser = { id: 99 }
    const w = await render(true)
    expect(btn(w, 'pi pi-pencil').exists()).toBe(true)
  })
})

describe('SkillList: カテゴリ取得', () => {
  const cat = (id: number, name: string, isActive: boolean) => ({ id, name, description: null, icon: null, sortOrder: id, isActive, createdAt: '' })

  it('名前解決のため無効化済みも含めて取得し、無効カテゴリの資格にもカテゴリ名が出る', async () => {
    api.getSkillCategories.mockResolvedValue({ data: [cat(7, '旧運転', false)] })
    const w = await render(true)
    expect(api.getSkillCategories).toHaveBeenCalledWith('e2e', true)
    expect(w.text()).toContain('旧運転')
  })

  it('絞り込みの選択肢には有効カテゴリだけを出す', async () => {
    api.getSkillCategories.mockResolvedValue({ data: [cat(7, '旧運転', false), cat(8, '救急', true)] })
    const w = await render(true)
    const options = w.find('[data-ph="カテゴリ"]').text()
    expect(options).toContain('救急')
    expect(options).not.toContain('旧運転')
  })

  it('取得に失敗したら握りつぶさずエラー通知する', async () => {
    api.getSkillCategories.mockRejectedValue(new Error('500'))
    await render(true)
    expect(notify.error).toHaveBeenCalledWith('skill.list.loadCategoriesFailed')
  })
})

describe('SkillList: F-AC7 証明書は hasCertificate のときだけ・資格番号は出さない', () => {
  it('hasCertificate=false なら本人でも証明書ボタンを出さない', async () => {
    api.searchSkills.mockResolvedValue({ data: [searchItem({ hasCertificate: false })] })
    const w = await render(true)
    expect(btn(w, 'pi pi-download').exists()).toBe(false)
    expect(btn(w, 'pi pi-pencil').exists()).toBe(true)
  })

  it('hasCertificate=true でも本人でも管理者でもなければ証明書ボタンを出さない', async () => {
    auth.currentUser = { id: 99 }
    const w = await render(false)
    expect(btn(w, 'pi pi-download').exists()).toBe(false)
  })

  it('hasCertificate=true かつ本人なら証明書ボタンを出す', async () => {
    const w = await render(false)
    expect(btn(w, 'pi pi-download').exists()).toBe(true)
  })

  it('資格番号は（API が返してきても）表示しない', async () => {
    api.searchSkills.mockResolvedValue({ data: [searchItem({ credentialNumber: 'No.SECRET-999' })] })
    const w = await render(true)
    expect(w.text()).not.toContain('No.SECRET-999')
  })
})

describe('SkillList: F-AC8 削除は確認ダイアログを経る', () => {
  it('削除ボタンで useConfirmDialog().confirmAction が呼ばれ、承諾前は API を呼ばない', async () => {
    confirm.confirmAction.mockImplementation(() => {})
    const w = await render(true)
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(confirm.confirmAction).toHaveBeenCalledTimes(1)
    expect(api.deleteSkill).not.toHaveBeenCalled()
  })

  it('キャンセル（onReject）なら削除 API を呼ばない', async () => {
    confirm.confirmAction.mockImplementation((o: { onReject?: () => void }) => o.onReject?.())
    const w = await render(true)
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(api.deleteSkill).not.toHaveBeenCalled()
  })

  it('承諾すると削除 API を呼び、成功通知して一覧を再取得する', async () => {
    const w = await render(true)
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(api.deleteSkill).toHaveBeenCalledWith('e2e', 501)
    expect(notify.success).toHaveBeenCalled()
    expect(api.searchSkills).toHaveBeenCalledTimes(2)
  })

  it('削除 API が失敗したら行を消さずエラー通知する', async () => {
    api.deleteSkill.mockRejectedValue(new Error('500'))
    const w = await render(true)
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(notify.error).toHaveBeenCalled()
    expect(api.searchSkills).toHaveBeenCalledTimes(1)
    expect(w.text()).toContain('普通自動車免許')
  })
})

describe('SkillList: F-AC3 scope=mine は自分の資格（/skills/me）だけを使う', () => {
  it('search を呼ばず getMySkills で取得し、資格名とカテゴリ名を出す', async () => {
    const w = await render(false, 'mine')
    expect(api.getMySkills).toHaveBeenCalledWith('e2e')
    expect(api.searchSkills).not.toHaveBeenCalled()
    expect(w.text()).toContain('普通自動車免許')
    expect(w.text()).toContain('運転')
  })

  it('一般メンバーでも「スキル登録」ボタンが出て create を emit する', async () => {
    const w = await render(false, 'mine')
    await btn(w, 'pi pi-plus').trigger('click')
    expect(w.emitted('create')).toHaveLength(1)
  })

  it('取得失敗は握りつぶさずエラー通知する', async () => {
    api.getMySkills.mockRejectedValue(new Error('500'))
    await render(false, 'mine')
    expect(notify.error).toHaveBeenCalled()
  })

  it('mine でも hasCertificate=false なら証明書ボタンは出ない', async () => {
    api.getMySkills.mockResolvedValue({ data: [{ ...fullSkill, hasCertificate: false }] })
    const w = await render(false, 'mine')
    expect(btn(w, 'pi pi-download').exists()).toBe(false)
  })
})

describe('SkillList: F-AC6 カテゴリ再取得', () => {
  const cat = (id: number, name: string) => ({ id, name, description: null, icon: null, sortOrder: id, isActive: true, createdAt: '' })
  const refresh = (w: VueWrapper) => (w.vm as unknown as { refreshCategories: () => Promise<void> }).refreshCategories()

  it('refreshCategories でカテゴリを再取得し、新カテゴリ名が名前解決・選択肢に反映される', async () => {
    api.getSkillCategories.mockResolvedValue({ data: [cat(7, '運転')] })
    const w = await render(true)
    api.getSkillCategories.mockResolvedValue({ data: [cat(7, '運転（改）'), cat(9, '新規')] })
    await refresh(w)
    await flushPromises()
    expect(w.text()).toContain('運転（改）')
    expect(w.find('[data-ph="カテゴリ"]').text()).toContain('新規')
  })

  it('選択中のカテゴリが削除されたらフィルタを解除して一覧を取り直す', async () => {
    api.getSkillCategories.mockResolvedValue({ data: [cat(7, '運転'), cat(8, '救急')] })
    const w = await render(true)
    await w.find('[data-ph="カテゴリ"] button[data-v="8"]').trigger('click')
    await flushPromises()
    expect(api.searchSkills.mock.calls.at(-1)?.[1]).toMatchObject({ categoryId: 8 })
    api.getSkillCategories.mockResolvedValue({ data: [cat(7, '運転')] })
    await refresh(w)
    await flushPromises()
    expect(api.searchSkills.mock.calls.at(-1)?.[1]).not.toHaveProperty('categoryId')
  })

  it('再取得に失敗したら握りつぶさずエラー通知する', async () => {
    const w = await render(true)
    notify.error.mockClear()
    api.getSkillCategories.mockRejectedValue(new Error('500'))
    await refresh(w)
    expect(notify.error).toHaveBeenCalledWith('skill.list.loadCategoriesFailed')
  })
})

describe('SkillList: ALIC-2 承認・削除の成功を親へ changed で知らせる（マトリクス再取得のため）', () => {
  it('承認に成功したら changed を emit する', async () => {
    const w = await render(true)
    await btn(w, 'pi pi-check').trigger('click')
    await flushPromises()
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('削除に成功したら changed を emit する', async () => {
    const w = await render(true)
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('承認に失敗したら changed を emit しない', async () => {
    api.verifySkill.mockRejectedValue(new Error('500'))
    const w = await render(true)
    await btn(w, 'pi pi-check').trigger('click')
    await flushPromises()
    expect(w.emitted('changed')).toBeUndefined()
  })

  it('削除に失敗したら changed を emit しない', async () => {
    api.deleteSkill.mockRejectedValue(new Error('500'))
    const w = await render(true)
    await btn(w, 'pi pi-trash').trigger('click')
    await flushPromises()
    expect(w.emitted('changed')).toBeUndefined()
  })
})

describe('SkillList: ALIC-3 アイコンだけのボタンにアクセシブルネーム（aria-label）がある', () => {
  it.each([
    ['pi pi-check', 'skill.list.actions.verify'],
    ['pi pi-pencil', 'skill.list.actions.edit'],
    ['pi pi-trash', 'skill.list.actions.delete'],
    ['pi pi-download', 'skill.list.actions.certificate'],
  ])('%s ボタンの aria-label は %s', async (icon, key) => {
    const w = await render(true)
    expect(btn(w, icon).attributes('aria-label')).toBe(key)
  })
})
