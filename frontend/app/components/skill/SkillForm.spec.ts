// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import SkillForm from './SkillForm.vue'

/**
 * CMP-261012-0313 ALIC-1: チームに有効なカテゴリが1件も無いと資格を登録できない
 * （BE の skillCategoryId は @NotNull）。フォームは行き止まりにせず、次の一手を案内する。
 */

const api = vi.hoisted(() => ({ registerSkill: vi.fn(), updateSkill: vi.fn(), getSkillUploadUrl: vi.fn() }))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
mockNuxtImport('useSkillApi', () => () => api)
mockNuxtImport('useNotification', () => () => notify)

const Dialog = defineComponent({
  setup: (_, { slots }) => () => h('div', [slots.default?.(), slots.footer?.()]),
})
const category = (id: number, name: string, isActive = true) => ({
  id, name, description: null, icon: null, sortOrder: id, isActive, createdAt: '',
})

let wrapper: VueWrapper | undefined
async function render(props: { categories: ReturnType<typeof category>[]; canManageCategories?: boolean }) {
  wrapper = mount(SkillForm, {
    props: { teamId: 'e2e', visible: true, skill: null, ...props },
    global: { stubs: { Dialog, Select: true, InputText: true, Button: true } },
  })
  await flushPromises()
  return wrapper
}
const hint = (w: VueWrapper) => w.find('[data-testid="skill-form-no-category"]')

beforeEach(() => vi.clearAllMocks())
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('SkillForm: ALIC-1 有効なカテゴリが0件のときの案内', () => {
  it('カテゴリ管理ができる人には「カテゴリ管理タブで作成」を案内する', async () => {
    const w = await render({ categories: [], canManageCategories: true })
    expect(hint(w).exists()).toBe(true)
    expect(hint(w).text()).toBe('skill.form.noCategoryAdmin')
  })

  it('一般メンバーには「管理者へ作成を依頼」を案内する', async () => {
    const w = await render({ categories: [], canManageCategories: false })
    expect(hint(w).exists()).toBe(true)
    expect(hint(w).text()).toBe('skill.form.noCategoryMember')
  })

  it('無効化済みカテゴリしか無い場合も案内する', async () => {
    const w = await render({ categories: [category(1, '旧', false)], canManageCategories: false })
    expect(hint(w).exists()).toBe(true)
    expect(hint(w).text()).toBe('skill.form.noCategoryMember')
  })

  it('有効なカテゴリがあれば案内は出ない', async () => {
    const w = await render({ categories: [category(1, '運転')], canManageCategories: true })
    expect(hint(w).exists()).toBe(false)
  })
})
