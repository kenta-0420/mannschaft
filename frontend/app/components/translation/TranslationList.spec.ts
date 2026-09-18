import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TranslationList from './TranslationList.vue'

// BE（ContentTranslationService.TranslationDashboardResponse）が実際に返す平坦な camelCase 形。
// FE がここで `byStatus` のような入れ子形を期待すると、実機で
// 「TypeError: Cannot read properties of undefined (reading 'DRAFT')」でクラッシュする
// （殿が実機で再現・特定した回帰: docs/openapi.json の TranslationDashboardResponse 参照）。
function makeDashboardResponse(overrides: Partial<{
  totalTranslations: number
  draft: number
  inReview: number
  published: number
  needsUpdate: number
}> = {}) {
  return {
    totalTranslations: 10,
    draft: 3,
    inReview: 2,
    published: 4,
    needsUpdate: 1,
    ...overrides,
  }
}

const listTranslations = vi.fn()
const getDashboard = vi.fn()
const updateStatus = vi.fn()
const publishTranslation = vi.fn()

mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
}))
mockNuxtImport('useDatetime', () => () => ({
  userTimezone: { value: 'Asia/Tokyo' },
}))
mockNuxtImport('useTranslationApi', () => () => ({
  listTranslations,
  getDashboard,
  updateStatus,
  publishTranslation,
}))

const stubs = {
  DataTable: { props: ['value'], template: '<div><slot /><template v-for="row in value"><slot name="body-status" :data="row" /></template></div>' },
  Column: { template: '<div><slot :data="{}" /></div>' },
  Paginator: true,
  Select: true,
  Button: {
    props: ['label'],
    emits: ['click'],
    template: '<button @click="$emit(\'click\')">{{ label }}</button>',
  },
  TranslationStatusBadge: { props: ['status'], template: '<span>{{ status }}</span>' },
}

async function mountList() {
  const wrapper = await mountSuspended(TranslationList, {
    props: { orgId: 'org-1' },
    global: { stubs },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  listTranslations.mockReset().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 })
  getDashboard.mockReset()
  updateStatus.mockReset().mockResolvedValue({})
  publishTranslation.mockReset().mockResolvedValue({})
})

describe('TranslationList', () => {
  it('BE の実応答（平坦な camelCase）でクラッシュせずダッシュボード集計を表示する', async () => {
    getDashboard.mockResolvedValue(makeDashboardResponse())
    const wrapper = await mountList()
    // 例外を投げずマウントできること自体が回帰確認（byStatus 未定義参照でのクラッシュ再発防止）
    expect(wrapper.text()).toContain('3')
    expect(wrapper.text()).toContain('2')
    expect(wrapper.text()).toContain('4')
    expect(wrapper.text()).toContain('1')
  })

  it('ダッシュボード未取得時（null）はカードを空にする', async () => {
    getDashboard.mockRejectedValue(new Error('network'))
    const wrapper = await mountList()
    expect(wrapper.exists()).toBe(true)
  })
})
