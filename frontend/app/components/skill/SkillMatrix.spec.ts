// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import SkillMatrix from './SkillMatrix.vue'

/**
 * CMP-261012-0313 F-AC5: スキルマトリクスを BE の実形
 * `{ categories, rows: [{ userId, displayName, cells: { [categoryId]: { memberSkillId, status, expiresAt } } }] }`
 * で描画する。
 */

const api = vi.hoisted(() => ({ getSkillMatrix: vi.fn() }))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
mockNuxtImport('useSkillApi', () => () => api)
mockNuxtImport('useNotification', () => () => notify)

const Button = defineComponent({
  props: { icon: { type: String, default: '' } },
  emits: ['click'],
  setup(p, { emit }) {
    return () => h('button', { type: 'button', 'data-icon': p.icon, onClick: () => emit('click') })
  },
})

const category = (id: number, name: string) => ({
  id, name, description: null, icon: null, sortOrder: id, isActive: true, createdAt: '',
})

let wrapper: VueWrapper | undefined
async function render() {
  wrapper = mount(SkillMatrix, {
    props: { teamId: 'e2e' },
    global: { stubs: { Button, LoadingBounce: true }, directives: { tooltip: {} } },
  })
  await flushPromises()
  return wrapper
}
const cell = (w: VueWrapper, userId: number, categoryId: number) => w.find(`[data-testid="cell-${userId}-${categoryId}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  api.getSkillMatrix.mockResolvedValue({
    data: {
      categories: [category(1, '運転'), category(2, '救急')],
      rows: [
        {
          userId: 10,
          displayName: '山田太郎',
          cells: {
            1: { memberSkillId: 501, status: 'ACTIVE', expiresAt: '2030-01-01' },
            2: { memberSkillId: 502, status: 'EXPIRED', expiresAt: '2020-01-01' },
          },
        },
        {
          userId: 11,
          displayName: '鈴木花子',
          cells: {
            1: { memberSkillId: null, status: null, expiresAt: null },
          },
        },
      ],
    },
  })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('SkillMatrix: BE の実形（rows / cells）で描画する', () => {
  it('メンバー表示名とカテゴリ列見出しが出る', async () => {
    const w = await render()
    expect(w.text()).toContain('山田太郎')
    expect(w.text()).toContain('鈴木花子')
    expect(w.text()).toContain('運転')
    expect(w.text()).toContain('救急')
  })

  it('セルの状態が 有効 / 期限切れ になる', async () => {
    const w = await render()
    expect(cell(w, 10, 1).attributes('data-status')).toBe('ACTIVE')
    expect(cell(w, 10, 2).attributes('data-status')).toBe('EXPIRED')
  })

  it('確認待ち（PENDING_REVIEW）は BE 仕様どおり「未登録」として扱う', async () => {
    api.getSkillMatrix.mockResolvedValue({
      data: {
        categories: [category(1, '運転')],
        rows: [{ userId: 10, displayName: '山田', cells: { 1: { memberSkillId: 5, status: 'PENDING_REVIEW', expiresAt: null } } }],
      },
    })
    const w = await render()
    expect(cell(w, 10, 1).attributes('data-status')).toBe('NONE')
  })

  it('cells にキーが無いカテゴリは「未登録」になる', async () => {
    const w = await render()
    expect(cell(w, 11, 2).attributes('data-status')).toBe('NONE')
  })

  it('status が null のセルは「未登録」になる（expiresAt null でも落ちない）', async () => {
    const w = await render()
    expect(cell(w, 11, 1).attributes('data-status')).toBe('NONE')
  })

  it('expiresAt が null の有効セルでも描画できる', async () => {
    api.getSkillMatrix.mockResolvedValue({
      data: {
        categories: [category(1, '運転')],
        rows: [{ userId: 10, displayName: '山田', cells: { 1: { memberSkillId: 5, status: 'ACTIVE', expiresAt: null } } }],
      },
    })
    const w = await render()
    expect(cell(w, 10, 1).attributes('data-status')).toBe('ACTIVE')
  })
})

describe('SkillMatrix: 空・失敗', () => {
  it('categories が 0 件なら既存の空表示文言を出す', async () => {
    api.getSkillMatrix.mockResolvedValue({ data: { categories: [], rows: [{ userId: 10, displayName: '山田', cells: {} }] } })
    const w = await render()
    expect(w.text()).toContain('マトリクスデータがありません')
  })

  it('rows が 0 件なら既存の空表示文言を出す', async () => {
    api.getSkillMatrix.mockResolvedValue({ data: { categories: [category(1, '運転')], rows: [] } })
    const w = await render()
    expect(w.text()).toContain('マトリクスデータがありません')
  })

  it('取得に失敗したら握りつぶさずエラー通知する', async () => {
    api.getSkillMatrix.mockRejectedValue(new Error('403'))
    await render()
    expect(notify.error).toHaveBeenCalled()
  })
})

describe('SkillMatrix: ALIC-3 更新ボタンにアクセシブルネーム（aria-label）がある', () => {
  it('pi-refresh ボタンの aria-label は skill.matrix.refresh', async () => {
    const w = await render()
    expect(w.find('button[data-icon="pi pi-refresh"]').attributes('aria-label')).toBe('skill.matrix.refresh')
  })
})

describe('SkillMatrix: ALIC-4凡例は実際に現れる状態だけを出す', () => {
  it('凡例に「確認待ち」が無い（BE は確認待ちを未登録として返すため決して現れない）', async () => {
    const w = await render()
    expect(w.text()).not.toContain('確認待ち')
  })

  it('確認待ちは未登録として表示される旨の注記を出す', async () => {
    const w = await render()
    expect(w.text()).toContain('skill.matrix.legendPendingNote')
  })
})
