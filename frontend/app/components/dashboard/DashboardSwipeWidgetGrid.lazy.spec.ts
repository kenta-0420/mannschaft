// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import type { TeamDashboardResponse } from '~/types/dashboard-scope'
import type { ScopeWidgetPreviewState } from '~/composables/useScopeWidgetPreview'
import Grid from './DashboardSwipeWidgetGrid.vue'

const state = ref<ScopeWidgetPreviewState>('CLOSED')
const close = vi.fn()
const retry = vi.fn()
vi.mock('~/composables/useScopeWidgetPreview', () => ({
  useScopeWidgetPreview: () => ({
    state, target: ref(null), detail: ref(null), error: ref(null), trigger: ref(null),
    close, retry, open: vi.fn(),
  }),
}))
const PreviewStub = defineComponent({
  name: 'PreviewStub',
  props: ['state', 'target', 'detail', 'error', 'trigger'],
  emits: ['close', 'retry'],
  template: '<section data-testid="preview-stub" />',
})
let wrapper: VueWrapper | undefined
beforeEach(() => {
  state.value = 'CLOSED'
  vi.clearAllMocks()
})
afterEach(() => wrapper?.unmount())
function render() {
  wrapper = mount(Grid, {
    props: { scopeType: 'TEAM', scopeId: 'team-a', numericScopeId: '1', data: {} as TeamDashboardResponse },
    global: {
      mocks: { $t: (key: string) => key },
      components: { LazyDashboardScopePreviewModal: PreviewStub },
      stubs: {
        DashboardScopeListWidget: true, DashboardWidgetCard: true, DashboardScopeWidgetItem: true,
        TimelineFeed: true, ScopeActionRequiredWidget: true, DashboardEmptyState: true,
        DashboardScopePreviewModal: PreviewStub, LazyDashboardScopePreviewModal: PreviewStub,
      },
    },
  })
  return wrapper
}

describe('DashboardSwipeWidgetGrid プレビューの遅延表示', () => {
  it('初回CLOSEDではプレビューを生成しない', () => {
    expect(render().findComponent(PreviewStub).exists()).toBe(false)
  })

  it('最初のLOADINGから表示し、closeとretryを既存処理へ渡す', async () => {
    const view = render()
    state.value = 'LOADING'
    await vi.waitFor(() => expect(view.findComponent(PreviewStub).exists()).toBe(true))
    const preview = view.getComponent(PreviewStub)
    expect(preview.props('state')).toBe('LOADING')
    preview.vm.$emit('retry')
    preview.vm.$emit('close')
    expect(retry).toHaveBeenCalledOnce()
    expect(close).toHaveBeenCalledOnce()
  })

  it('close後も同じインスタンスを保持して再表示する', async () => {
    const view = render()
    state.value = 'LOADING'
    await vi.waitFor(() => expect(view.findComponent(PreviewStub).exists()).toBe(true))
    const instance = view.getComponent(PreviewStub).vm
    state.value = 'CLOSED'
    await nextTick()
    expect(view.getComponent(PreviewStub).vm).toBe(instance)
    expect(view.getComponent(PreviewStub).props('state')).toBe('CLOSED')
    state.value = 'FULL'
    await nextTick()
    expect(view.getComponent(PreviewStub).vm).toBe(instance)
    expect(view.getComponent(PreviewStub).props('state')).toBe('FULL')
  })
})
