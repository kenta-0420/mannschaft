import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'
import { useNuxtApp, useRouter } from '#imports'
import NuxtLink from '#app/components/nuxt-link'
import GlobalNavItem from './GlobalNavItem.vue'

describe('グローバルナビの操作時先読み', () => {
  it.each(['pointerenter', 'focus'] as const)('可視化だけでは先読みせず、%s とクリック遷移を保つ', async (interaction) => {
    const nuxt = useNuxtApp()
    const router = useRouter()
    const previousRoute = router.currentRoute.value.fullPath
    const previousObserver = nuxt._observer
    const observed = new Map<Element, () => void>()
    nuxt._observer = {
      observe(element, callback) {
        observed.set(element, callback)
        return () => { observed.delete(element) }
      },
    }
    const prefetch = vi.fn()
    const unhook = nuxt.hooks.hook('link:prefetch', prefetch)
    const destination = `/__nav-interaction-${interaction}`
    const controlDestination = `/__nav-visibility-${interaction}`
    const importer = vi.fn(async () => defineComponent({ render: () => h('div', '遷移先') }))
    const removeDestination = router.addRoute({ path: destination, component: importer })
    const removeControl = router.addRoute({ path: controlDestination, component: defineComponent({ render: () => h('div') }) })
    const Host = defineComponent({
      render: () => h('div', [
        h(GlobalNavItem, { item: { key: 'probe', labelKey: 'probe', icon: 'pi pi-calendar', path: destination }, rail: false, active: false }),
        // 実NuxtLinkの可視先読みを陽性対照にし、observer/hook自体が動くことを確認する。
        h(NuxtLink, { to: controlDestination, prefetchOn: 'visibility' }, { default: () => '可視先読みの対照' }),
      ]),
    })
    let unmount: (() => void) | undefined
    try {
      const wrapper = await mountSuspended(Host, {
        attachTo: document.body,
        global: { components: { NuxtLink }, stubs: { NuxtLink: false } },
      })
      unmount = () => wrapper.unmount()
      const link = wrapper.get(`a[href="${destination}"]`)
      const control = wrapper.get(`a[href="${controlDestination}"]`)
      await vi.waitFor(() => expect(observed.has(control.element)).toBe(true))
      for (const callback of [...observed.values()]) callback()
      await flushPromises()
      expect(prefetch).toHaveBeenCalledWith(controlDestination)
      expect(prefetch.mock.calls.filter(([to]) => to === destination)).toHaveLength(0)
      expect(importer).not.toHaveBeenCalled()

      await link.trigger(interaction)
      await flushPromises()
      expect(prefetch.mock.calls.filter(([to]) => to === destination)).toHaveLength(1)
      // Nuxtの開発用テスト環境ではhover時のroute importが省略されるため、
      // ここでは実hookを確認し、実routerのasync importerはクリック遷移で確認する。
      await link.trigger('click')
      await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(destination))
      expect(importer).toHaveBeenCalledOnce()
    } finally {
      unmount?.()
      unhook()
      nuxt._observer = previousObserver
      await router.push(previousRoute)
      removeDestination()
      removeControl()
    }
  })
})
