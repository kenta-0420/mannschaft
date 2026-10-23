// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import type {
  ScopeWidgetPreviewDetail,
  ScopeWidgetPreviewState,
} from '~/composables/useScopeWidgetPreview'
import Modal from './DashboardScopePreviewModal.vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('~/composables/useDatetime', () => ({
  useDatetime: () => ({
    formatDate: (value: string) => value,
    formatDateTime: (value: string) => value,
  }),
}))
const Dialog = defineComponent({
  props: ['visible', 'style', 'breakpoints', 'closeButtonProps'],
  emits: ['update:visible', 'after-hide'],
  template: '<div v-if="visible" role="dialog"><slot /><slot name="footer" /></div>',
})
const Button = defineComponent({ props: ['label'], template: '<button>{{ label }}</button>' })
const ErrorState = defineComponent({
  props: ['showRetry'],
  emits: ['retry'],
  template:
    '<div data-testid="error"><button v-if="showRetry" @click="$emit(\'retry\')">retry</button></div>',
})
let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
function render(
  state: ScopeWidgetPreviewState,
  detail: ScopeWidgetPreviewDetail | null = null,
  trigger: HTMLElement | null = null,
) {
  wrapper = mount(Modal, {
    props: {
      state,
      detail,
      target: { kind: 'bulletin', id: 12, title: '概要' },
      error: null,
      trigger,
    },
    global: {
      stubs: {
        Dialog,
        Button,
        PageLoading: { template: '<div data-testid="loading" />' },
        DashboardErrorState: ErrorState,
        NuxtLink: defineComponent({ props: ['to'], template: '<a><slot /></a>' }),
      },
    },
  })
  return wrapper
}
const detail: ScopeWidgetPreviewDetail = {
  title: '詳細',
  body: '<p>取得した本文</p><script>alert(1)</script><img src="x" onerror="alert(1)"><a href="javascript:alert(1)">危険リンク</a>',
  bodyFormat: 'html',
  fields: [],
  route: '/teams/my-scope/bulletin?threadId=12',
}

describe('DashboardScopePreviewModal', () => {
  it.each(['CLOSED', 'LOADING', 'ERROR', 'UNAVAILABLE'] as const)(
    'AC5: %sでは古い本文とCTAを描画しない',
    (state) => {
      const view = render(state, detail)
      expect(view.find('[data-testid="scope-widget-preview-source"]').exists()).toBe(false)
      expect(view.find('[data-testid="scope-widget-preview-body"]').exists()).toBe(false)
      if (state === 'LOADING') expect(view.find('[data-testid="loading"]').exists()).toBe(true)
      if (state === 'UNAVAILABLE')
        expect(view.findComponent(ErrorState).props('showRetry')).toBe(false)
    },
  )
  it('AC7: 掲示板HTMLのscript・イベント属性・危険URLを除去する', () => {
    const view = render('FULL', detail)
    const body = view.get('[data-testid="scope-widget-preview-body"]')
    expect(body.text()).toContain('取得した本文')
    expect(body.find('script').exists()).toBe(false)
    expect(body.find('img').attributes('onerror')).toBeUndefined()
    expect(body.find('a').attributes('href')).toBeUndefined()
    expect(view.find('[data-testid="scope-widget-preview-source"]').exists()).toBe(true)
  })
  it('AC7: ブログMarkdownも既存サニタイズを通す', () => {
    const view = render('FULL', {
      ...detail,
      bodyFormat: 'markdown',
      body: '**本文**<script>alert(1)</script>',
    })
    expect(view.get('[data-testid="scope-widget-preview-body"] strong').text()).toBe('本文')
    expect(view.find('script').exists()).toBe(false)
  })
  it('AC2: TODO説明はエスケープし空本文も正常に表示する', async () => {
    const view = render('FULL', { ...detail, bodyFormat: 'text', body: '<img onerror="evil()">' })
    expect(view.get('[data-testid="scope-widget-preview-body"]').text()).toBe(
      '<img onerror="evil()">',
    )
    expect(view.find('img').exists()).toBe(false)
    await view.setProps({ detail: { ...detail, body: '', bodyFormat: 'text' } })
    expect(view.text()).toContain('swipeWidgets.preview.emptyBody')
    expect(view.find('[data-testid="scope-widget-preview-source"]').exists()).toBe(true)
  })
  it('AC5/8: 現対象の再試行・閉じる・mask/Esc相当の非表示イベントを親へ伝える', async () => {
    const view = render('ERROR')
    await view.get('[data-testid="error"] button').trigger('click')
    expect(view.emitted('retry')).toHaveLength(1)
    await view.get('[data-testid="scope-widget-preview-close"]').trigger('click')
    view.getComponent(Dialog).vm.$emit('update:visible', false)
    expect(view.emitted('close')).toHaveLength(2)
    expect(view.get('[data-testid="scope-widget-preview-close"]').classes()).toContain('min-h-11')
    expect(view.getComponent(Dialog).props('breakpoints')).toEqual({
      '767px': 'calc(100vw - 1rem)',
    })
  })
  it('AC8: 閉じた後に起点へフォーカスを戻す', () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    const view = render('FULL', detail, trigger)
    view.getComponent(Dialog).vm.$emit('after-hide')
    expect(document.activeElement).toBe(trigger)
  })
})
