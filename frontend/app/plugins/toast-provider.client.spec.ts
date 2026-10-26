// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import type { NuxtApp } from '#app'
import PrimeVue from 'primevue/config'
import Toast from 'primevue/toast'
import type { ToastMessageOptions } from 'primevue/toast'
import ToastService, { type ToastServiceMethods } from 'primevue/toastservice'
import ToastEventBus from 'primevue/toasteventbus'
import toastProvider from './toast-provider.client'

const GROUP = 'initial-notification-regression'
const apps: App[] = []
const containers: HTMLElement[] = []
const cleanupListeners: (() => void)[] = []

afterEach(() => {
  cleanupListeners.splice(0).forEach(cleanup => cleanup())
  apps.splice(0).forEach(app => app.unmount())
  containers.splice(0).forEach(container => container.remove())
})

function message(summary: string, group = GROUP): ToastMessageOptions {
  return { summary, group, life: 0 }
}

/** Nuxt の mount hook だけを制御し、通知サービス・購読・描画は実 PrimeVue を通す。 */
async function prepare() {
  let mounted: (() => void) | undefined
  const app = createApp(defineComponent({
    setup: () => () => h('div', [
      h(Toast, { group: GROUP, 'data-testid': 'initial-toast' }),
      h(Toast, { group: `${GROUP}-other`, 'data-testid': 'other-toast' }),
    ]),
  }))
  app.use(PrimeVue, { unstyled: true })
  app.use(ToastService)
  const nuxtApp = {
    hook(name: string, callback: () => void) {
      if (name === 'app:mounted') mounted = callback
    },
  } as unknown as NuxtApp
  const provided = await app.runWithContext(() => toastProvider(nuxtApp))
  const toast = provided?.provide?.toast as ToastServiceMethods
  const rawToast = app.config.globalProperties.$toast as ToastServiceMethods
  const container = document.createElement('div')
  document.body.append(container)
  containers.push(container)

  return {
    toast,
    rawToast,
    async mount() {
      app.mount(container)
      apps.push(app)
      await nextTick()
    },
    async finishMount() {
      mounted?.()
      await nextTick()
    },
    summaries() {
      return [...document.querySelectorAll('[data-testid="initial-toast"] [data-pc-section="summary"]')]
        .map(element => element.textContent)
    },
  }
}

describe('初期遷移のトースト通知', () => {
  it('(AC-1) 初期購読前の通知を実 Toast の mount 後に表示する', async () => {
    const fixture = await prepare()
    fixture.toast.add(message('管理者権限が必要です'))
    await fixture.mount()
    expect(fixture.summaries()).toEqual([])

    await fixture.finishMount()

    expect(fixture.summaries()).toEqual(['管理者権限が必要です'])
  })

  it('(AC-2) 複数通知の順序とオプション同一性を保ち再送しない', async () => {
    const fixture = await prepare()
    const first = message('一番目')
    const second = message('二番目')
    fixture.toast.add(first)
    fixture.toast.add(second)
    await fixture.mount()
    await fixture.finishMount()
    // 実 Toast が同じメッセージオブジェクトへ ID を付けていることも確認する。
    expect(first.id).toBeDefined()
    expect(second.id).toBeDefined()
    await fixture.finishMount()

    expect(fixture.summaries()).toEqual(['一番目', '二番目'])
  })

  it('(AC-2) mount 後の通知は次の mount hook を待たず表示する', async () => {
    const fixture = await prepare()
    await fixture.mount()
    await fixture.finishMount()

    fixture.toast.add(message('通常通知'))
    await nextTick()

    expect(fixture.summaries()).toEqual(['通常通知'])
  })

  it('(AC-2) 内容が同じ別々の通知を勝手に間引かない', async () => {
    const fixture = await prepare()
    fixture.toast.add(message('同じ内容'))
    fixture.toast.add(message('同じ内容'))
    await fixture.mount()
    await fixture.finishMount()

    expect(fixture.summaries()).toEqual(['同じ内容', '同じ内容'])
  })

  it('(AC-3) 空の保留通知は何も表示しない', async () => {
    const fixture = await prepare()
    await fixture.mount()
    await fixture.finishMount()

    expect(fixture.summaries()).toEqual([])
  })

  it('(AC-4) mount 前の remove は対象通知だけを取り消す', async () => {
    const fixture = await prepare()
    const removed = message('取り消す')
    fixture.toast.add(message('残す'))
    fixture.toast.add(removed)
    fixture.toast.remove(removed)
    await fixture.mount()
    await fixture.finishMount()

    expect(fixture.summaries()).toEqual(['残す'])
  })

  it('(AC-4) mount 前の removeGroup は指定グループだけを取り消す', async () => {
    const fixture = await prepare()
    fixture.toast.add(message('取り消す'))
    fixture.toast.add(message('別グループ', `${GROUP}-other`))
    fixture.toast.removeGroup(GROUP)
    fixture.toast.add(message('取消後の通知'))
    await fixture.mount()
    await fixture.finishMount()

    expect(fixture.summaries()).toEqual(['取消後の通知'])
    expect(document.querySelector('[data-testid="other-toast"] [data-pc-section="summary"]')?.textContent)
      .toBe('別グループ')
  })

  it('(AC-4) mount 前の removeAllGroups より後の通知は維持する', async () => {
    const fixture = await prepare()
    fixture.toast.add(message('取り消す'))
    fixture.toast.add(message('別グループも取り消す', `${GROUP}-other`))
    fixture.toast.removeAllGroups()
    fixture.toast.add(message('全取消後の通知'))
    await fixture.mount()
    await fixture.finishMount()

    expect(fixture.summaries()).toEqual(['全取消後の通知'])
    expect(document.querySelector('[data-testid="other-toast"] [data-pc-section="summary"]')).toBeNull()
  })

  it('(AC-4) mount 後も remove・removeGroup・removeAllGroups を実サービスへ渡す', async () => {
    const fixture = await prepare()
    await fixture.mount()
    await fixture.finishMount()
    const removed = message('個別取消')
    fixture.toast.add(removed)
    fixture.toast.remove(removed)
    fixture.toast.add(message('グループ取消'))
    fixture.toast.removeGroup(GROUP)
    fixture.toast.add(message('全取消'))
    fixture.toast.removeAllGroups()
    await nextTick()

    expect(fixture.summaries()).toEqual([])
  })

  it('(AC-4) 配信先の例外を握りつぶさず既送通知を再送しない', async () => {
    const fixture = await prepare()
    const error = new Error('配信先の失敗')
    const fail = () => { throw error }
    fixture.toast.add(message('失敗する通知'))
    await fixture.mount()
    ToastEventBus.on('add', fail)
    cleanupListeners.push(() => ToastEventBus.off('add', fail))

    await expect(fixture.finishMount()).rejects.toBe(error)
    ToastEventBus.off('add', fail)
    await fixture.finishMount()

    expect(fixture.summaries()).toEqual(['失敗する通知'])
    // 元サービスも同じエラーを伝播する（provider がエラー契約を変更しない）。
    ToastEventBus.on('add', fail)
    expect(() => fixture.rawToast.add(message('元サービス'))).toThrow(error)
  })
})
