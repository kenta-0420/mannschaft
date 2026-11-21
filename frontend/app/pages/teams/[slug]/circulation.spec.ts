import { computed, defineComponent, Fragment, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CirculationPage from '~/pages/teams/[slug]/circulation.vue'

const state = vi.hoisted(() => ({ ok: true, role: 'ADMIN' }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 't' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: computed(() => ['ADMIN', 'DEPUTY_ADMIN'].includes(state.role)),
  loadPermissions: vi.fn(async () => ({ ok: state.ok })),
}))

const stub = (testid: string) => defineComponent({ setup() { return () => h('div', { 'data-testid': testid }) } })
let wrapper: Awaited<ReturnType<typeof mountPage>> | undefined
async function mountPage() {
  return mountSuspended(CirculationPage, {
    global: {
      stubs: {
        PageHeader: stub('page-header'),
        PageLoading: stub('page-loading'),
        ConfirmableCirculationGuide: stub('guide'),
        CirculationList: stub('circulation-list'),
        CirculationDetailModal: true,
        CirculationCreateModal: true,
      },
    },
  })
}

/** 描画ツリーの最上位が Fragment（= 複数ルート / コメント混在）でないことを検査する。 */
function expectSingleRoot(w: NonNullable<typeof wrapper>) {
  const subTree = w.vm.$.subTree
  expect(subTree.type).not.toBe(Fragment)
  expect(subTree.el).toBeInstanceOf(HTMLElement)
}

describe('CMP-261013-0821 チーム回覧ページの単一ルート', () => {
  beforeEach(() => { state.ok = true; state.role = 'ADMIN' })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined })

  it('成功パスは単一ルートで本文（一覧）を描画する', async () => {
    wrapper = await mountPage()
    expectSingleRoot(wrapper)
    expect(wrapper.find('[data-testid="circulation-list"]').exists()).toBe(true)
  })

  it('権限取得失敗のエラー面も単一ルートで再試行ボタンを描画する', async () => {
    state.ok = false
    wrapper = await mountPage()
    expectSingleRoot(wrapper)
    expect(wrapper.find('[data-testid="circulation-list"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('common.scopeShell.load_error_title')
  })
})
