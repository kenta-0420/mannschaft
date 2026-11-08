import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import WidgetRanch from '~/components/widgets/WidgetRanch.vue'
import DashboardWidgetCard from '~/components/DashboardWidgetCard.vue'
import DashboardPersonalWidgetGrid from '~/components/dashboard/DashboardPersonalWidgetGrid.vue'
import type { WidgetDefinition } from '~/composables/useDashboardWidgets'
import type { RanchState } from '~/types/ranch'

const fixture = vi.hoisted(() => ({ create: vi.fn(), load: vi.fn() }))
mockNuxtImport('useRanchState', () => () => fixture.create())
const cleanups: (() => void)[] = []
function remember<T extends { unmount: () => void }>(wrapper: T): T {
 cleanups.push(() => wrapper.unmount())
 return wrapper
}
beforeEach(() => {
 fixture.load.mockReset()
 fixture.create.mockReturnValue({
  state: ref<RanchState | null>(null), loading: ref(false), failed: ref(false), load: fixture.load,
  api: { command: { pending: ref(null), running: ref(false) } },
 })
})
afterEach(() => {
 for (const cleanup of cleanups.splice(0)) cleanup()
 vi.restoreAllMocks()
})
describe('恐竜widgetの同期activationとcontrolled collapse', () => {
 it('初回active=falseでは本人牧場を取得せずactive化後に一度取得する', async () => {
  const wrapper = remember(await mountSuspended(WidgetRanch, { props: { active: false } }))
  expect(fixture.load).not.toHaveBeenCalled()
  await wrapper.setProps({ active: true })
  expect(fixture.load).toHaveBeenCalledTimes(1)
  await wrapper.setProps({ active: false })
  await wrapper.setProps({ active: true })
  expect(fixture.load).toHaveBeenCalledTimes(1)
 })
 it('初回host collapsed=trueでは本人牧場を取得しない', async () => {
  const wrapper = remember(await mountSuspended(WidgetRanch, { attrs: { collapsed: true } }))
  expect(fixture.load).not.toHaveBeenCalled()
  expect(wrapper.get('[data-widget-collapsed]').attributes('data-widget-collapsed')).toBe('true')
 })
 it('初回hidden tabでは取得せずvisible化後に一度取得する', async () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
  remember(await mountSuspended(WidgetRanch))
  expect(fixture.load).not.toHaveBeenCalled()
  visibility.mockReturnValue('visible')
  document.dispatchEvent(new Event('visibilitychange'))
  expect(fixture.load).toHaveBeenCalledTimes(1)
 })
 it('mobile host→実Card→hostで開閉状態が往復し展開時に一度取得する', async () => {
  const widget: WidgetDefinition = {
   key: 'dinosaur-ranch', label: 'ranch', labelKey: 'ranch.title', icon: 'pi pi-sparkles',
   description: 'ranch', descriptionKey: 'ranch.optional', scope: ['personal'],
  }
  const wrapper = remember(await mountSuspended(DashboardPersonalWidgetGrid, {
   props: { widgets: [widget], collapsedKeys: new Set(['dinosaur-ranch']) },
  }))
  expect(fixture.load).not.toHaveBeenCalled()
  expect(wrapper.get('[data-widget-collapsed]').attributes('data-widget-collapsed')).toBe('true')
  await wrapper.get('[aria-label="ウィジェットを展開する"]').trigger('click')
  expect(wrapper.emitted('toggle-collapse')).toEqual([['dinosaur-ranch']])
  await wrapper.setProps({ collapsedKeys: new Set<string>() })
  expect(wrapper.get('[data-widget-collapsed]').attributes('data-widget-collapsed')).toBe('false')
  expect(fixture.load).toHaveBeenCalledTimes(1)
  await wrapper.setProps({ collapsedKeys: new Set(['dinosaur-ranch']) })
  expect(wrapper.get('[data-widget-collapsed]').attributes('data-widget-collapsed')).toBe('true')
  expect(fixture.load).toHaveBeenCalledTimes(1)
 })
 it('controlled指定のない既存Cardは内部の開閉状態を保つ', async () => {
  const wrapper = remember(await mountSuspended(DashboardWidgetCard, { props: { title: 'legacy' } }))
  expect(wrapper.get('[data-widget-collapsed]').attributes('data-widget-collapsed')).toBe('false')
  await wrapper.get('[aria-label="ウィジェットを折り畳む"]').trigger('click')
  expect(wrapper.get('[data-widget-collapsed]').attributes('data-widget-collapsed')).toBe('true')
  expect(wrapper.emitted('collapse-change')).toEqual([[true]])
 })
})
