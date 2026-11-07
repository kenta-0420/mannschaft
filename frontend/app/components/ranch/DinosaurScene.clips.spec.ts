// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import DinosaurScene from './DinosaurScene.vue'
import { developmentRanchClips } from '~/utils/ranch-development-clips'
import type { DinosaurSummary } from '~/types/ranch'
import ja from '~/locales/ja/ranch.json'
import en from '~/locales/en/ranch.json'
import zh from '~/locales/zh/ranch.json'
import ko from '~/locales/ko/ranch.json'
import es from '~/locales/es/ranch.json'
import de from '~/locales/de/ranch.json'
const entry = developmentRanchClips.find(
  (value) =>
    value.sourceSpecies === 'tyrannosaurus' &&
    value.stage === 'BABY' &&
    value.renderStyle === 'PIXEL',
)!
const dinosaur: DinosaurSummary = {
  id: '11111111-1111-4111-8111-111111111111',
  speciesKey: entry.speciesKey,
  variantKey: entry.variantKey,
  speciesCatalogVersion: '3',
  stage: 'BABY',
  habitat: 'LAND',
  name: 'Synthetic',
  namedAt: null,
  xp: '0',
  nextStageXp: null,
  version: '1',
  egg: null,
}
const callbacks = new Map<number, FrameRequestCallback>()
let seq = 0
let visibility: IntersectionObserverCallback
const images: TestImage[] = []
const disconnect = vi.fn()
class TestImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  naturalWidth = 0
  naturalHeight = 0
  private value = ''
  get src() {
    return this.value
  }
  set src(value: string) {
    this.value = value
    const image = developmentRanchClips
      .flatMap((item) => [item.fallback, ...Object.values(item.clips)])
      .find((item) => item.src === value)
    this.naturalWidth = image?.sourceWidth ?? 0
    this.naturalHeight = image?.sourceHeight ?? 0
  }
  constructor() {
    images.push(this)
  }
  removeAttribute(name: string) {
    if (name === 'src') this.src = ''
  }
}
beforeEach(() => {
  images.length = 0
  callbacks.clear()
  seq = 0
  disconnect.mockClear()
  vi.stubGlobal('Image', TestImage)
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      constructor(callback: IntersectionObserverCallback) {
        visibility = callback
      }
      observe() {}
      unobserve() {}
      disconnect() {
        disconnect()
      }
    },
  )
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {},
  }))
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    callbacks.set(++seq, callback)
    return seq
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => callbacks.delete(id))
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
function scene(active = true, paused = false) {
  return mount(DinosaurScene, {
    global: {
      plugins: [createI18n({ legacy: false, locale: 'ja', messages: { ja, en, zh, ko, es, de } })],
    },
    props: {
      dinosaur,
      renderStyle: 'PIXEL',
      motionMode: 'NORMAL',
      active,
      backgroundPaused: paused,
      clipContext: { production: false, isolatedDevelopment: true, clipsEnabled: true },
    },
  })
}
async function show() {
  visibility([{ isIntersecting: true }] as IntersectionObserverEntry[], {} as IntersectionObserver)
  await nextTick()
}
function tick(time: number) {
  const pending = [...callbacks.values()]
  callbacks.clear()
  for (const callback of pending) callback(time)
}
describe('CLIP05/07/10/11 v3 scene接続', () => {
  it.each(['hidden', 'viewport'])('初回%sでもactive sceneはimage0/RAF0を保つ', async (mode) => {
    if (mode === 'hidden')
      Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    const wrapper = scene()
    if (mode === 'hidden') await show()
    expect(images).toHaveLength(0)
    expect(callbacks.size).toBe(0)
    wrapper.unmount()
  })
  it('初回OS reducedはatlasを取らず同entry静止だけを表示する', async () => {
    vi.stubGlobal('matchMedia', () => ({
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    }))
    const wrapper = scene()
    await show()
    expect(images).toHaveLength(1)
    expect(images[0]!.src).toBe(entry.fallback.src)
    images[0]!.onload?.()
    await nextTick()
    expect(callbacks.size).toBe(0)
    wrapper.unmount()
  })
  it('初回hidden/inactive/viewport外はimage0/RAF0、非表示とunmountで全資源を止める', async () => {
    const remove = vi.spyOn(document, 'removeEventListener'),
      wrapper = scene(false)
    expect(images).toHaveLength(0)
    expect(callbacks.size).toBe(0)
    await show()
    expect(images).toHaveLength(0)
    await wrapper.setProps({ active: true })
    expect(images).toHaveLength(1)
    expect(callbacks.size).toBe(0)
    images[0]!.onload?.()
    await nextTick()
    expect(callbacks.size).toBe(1)
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await nextTick()
    expect(callbacks.size).toBe(0)
    wrapper.unmount()
    expect(disconnect).toHaveBeenCalledOnce()
    expect(remove.mock.calls.some((call) => call[0] === 'visibilitychange')).toBe(true)
  })
  it('低速feedは画像readyからnative1cycle、既存genericbounceは付けない', async () => {
    const wrapper = scene()
    await show()
    images[0]!.onload?.()
    await nextTick()
    tick(0)
    await wrapper.setProps({
      reaction: { key: 'DINOSAUR_FEED', dinosaurId: dinosaur.id, sequence: 1 },
    })
    expect(images[1]!.src).toBe(entry.clips.feed.src)
    expect(callbacks.size).toBe(0)
    expect(wrapper.find('[data-reaction-key]').attributes('style') ?? '').not.toContain(
      'translateY',
    )
    images[1]!.onload?.()
    await nextTick()
    tick(50000)
    await nextTick()
    expect(wrapper.find('figure').attributes('data-clip-action')).toBe('feed')
    tick(50000 + entry.clips.feed.durationMs)
    await nextTick()
    expect(wrapper.find('figure').attributes('data-clip-action')).toBe('idle')
    wrapper.unmount()
  })
  it('PAUSEDはambient停止、sameID TOUCHをhappyへ接続し古sequence/wrongIDは新actionを取消しない', async () => {
    const wrapper = scene(true, true)
    await show()
    images[0]!.onload?.()
    await nextTick()
    expect(callbacks.size).toBe(0)
    await wrapper.setProps({
      reaction: { key: 'DINOSAUR_TOUCH', dinosaurId: dinosaur.id, sequence: 2 },
    })
    expect(images[1]!.src).toBe(entry.clips.happy.src)
    images[1]!.onload?.()
    await nextTick()
    tick(0)
    await wrapper.setProps({
      reaction: { key: 'DINOSAUR_FEED', dinosaurId: dinosaur.id, sequence: 1 },
    })
    expect(wrapper.find('figure').attributes('data-clip-action')).toBe('happy')
    await wrapper.setProps({
      reaction: { key: 'DINOSAUR_TOUCH', dinosaurId: 'wrong', sequence: 3 },
    })
    expect(callbacks.size).toBe(1)
    tick(entry.clips.happy.durationMs)
    await nextTick()
    expect(callbacks.size).toBe(0)
    expect(wrapper.find('figure').attributes('data-clip-action')).toBe('idle')
    wrapper.unmount()
  })
  it('REDUCED/STOPPEDは同ID静止fallbackへ切替しRAFを0にする', async () => {
    const wrapper = scene()
    await show()
    images[0]!.onload?.()
    await nextTick()
    expect(callbacks.size).toBe(1)
    await wrapper.setProps({ motionMode: 'REDUCED' })
    expect(callbacks.size).toBe(0)
    expect(images[1]!.src).toBe(entry.fallback.src)
    images[1]!.onload?.()
    await nextTick()
    await wrapper.setProps({
      reaction: { key: 'DINOSAUR_TOUCH', dinosaurId: dinosaur.id, sequence: 1 },
    })
    expect(callbacks.size).toBe(0)
    await wrapper.setProps({ motionMode: 'STOPPED' })
    expect(callbacks.size).toBe(0)
    expect(images).toHaveLength(2)
    expect(wrapper.attributes('data-dinosaur-id')).toBe(dinosaur.id)
    wrapper.unmount()
  })
})
