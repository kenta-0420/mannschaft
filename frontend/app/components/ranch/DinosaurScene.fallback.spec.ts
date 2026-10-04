// @vitest-environment happy-dom
// 有限登録の合成1entryだけを与える。実承認pack・実ブラウザの描画合格ではない。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import ja from '~/locales/ja/ranch.json'
import en from '~/locales/en/ranch.json'
import zh from '~/locales/zh/ranch.json'
import ko from '~/locales/ko/ranch.json'
import es from '~/locales/es/ranch.json'
import de from '~/locales/de/ranch.json'
import type { DinosaurSummary } from '~/types/ranch'
import DinosaurScene from './DinosaurScene.vue'

vi.mock('~/utils/ranch-production-assets', () => ({
  productionRanchAssets: Object.freeze([Object.freeze({
    assetKey: 'synthetic-pixel', speciesKey: 'SYNTHETIC_SPECIES', variantKey: 'SYNTHETIC_VARIANT',
    stage: 'BABY', renderStyle: 'PIXEL', catalogVersion: '3', approval: 'APPROVED', origin: 'PRODUCTION',
    src: '/synthetic/atlas.png', fallbackSrc: '/synthetic/static.png',
    sourceWidth: 768, sourceHeight: 96, columnBoundaries: [0,96,192,288,384,480,576,672,768],
    rowBoundaries: [0,96], frames: 8, sourceSha256: 'a'.repeat(64),
  })]),
}))
const dinosaur: DinosaurSummary = {
  id: '11111111-1111-4111-8111-111111111111', speciesKey: 'SYNTHETIC_SPECIES', variantKey: 'SYNTHETIC_VARIANT',
  stage: 'BABY', habitat: 'LAND', name: 'Synthetic', xp: '0', nextStageXp: '40', version: '0',
  namedAt: null, speciesCatalogVersion: '3', egg: null,
}
let visibility: IntersectionObserverCallback
const images: SyntheticImage[] = []
class SyntheticImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  src = ''
  naturalWidth = 96
  naturalHeight = 96
  constructor() { images.push(this) }
  removeAttribute(attribute: string) { if (attribute === 'src') this.src = '' }
}
beforeEach(() => {
  images.length = 0
  vi.stubGlobal('Image', SyntheticImage)
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: IntersectionObserverCallback) { visibility = callback }
    observe() {} unobserve() {} disconnect() {}
  })
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }))
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  // 画像transport/資源境界に限定する。canvasの実画素は実ブラウザで別に検証する。
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
function scene(motionMode: 'NORMAL' | 'STOPPED') {
  return mount(DinosaurScene, {
    global: { plugins: [createI18n({ legacy: false, locale: 'en', messages: { ja,en,zh,ko,es,de } })] },
    props: { dinosaur, renderStyle: 'PIXEL', motionMode, active: false, assetContext: { production: true, isolatedDevelopment: false, publicationEnabled: true } },
  })
}
async function show() {
  visibility([{ isIntersecting: true }] as IntersectionObserverEntry[], {} as IntersectionObserver)
  await nextTick()
}
describe('有限static fallbackの先行試練（未実測）', () => {
  it('inactive取得0、atlas失敗後は同entry静止だけ取得しhiddenで旧callbackを解除する', async () => {
    const wrapper = scene('NORMAL')
    try {
      await show()
      expect(images).toHaveLength(0)
      await wrapper.setProps({ active: true })
      expect(images).toHaveLength(1)
      expect(images[0]?.src).toBe('/synthetic/atlas.png')
      images[0]?.onerror?.()
      await nextTick()
      expect(images).toHaveLength(2)
      const fallback = images[1]
      if (!fallback) throw new Error('STATIC_FALLBACK_MISSING')
      expect(fallback.src).toBe('/synthetic/static.png')
      Object.defineProperty(document, 'hidden', { configurable: true, value: true })
      document.dispatchEvent(new Event('visibilitychange'))
      await nextTick()
      expect(fallback.onload).toBeNull()
      expect(fallback.onerror).toBeNull()
      expect(fallback.src).toBe('')
      expect(requestAnimationFrame).not.toHaveBeenCalled()
      expect(wrapper.attributes('data-dinosaur-id')).toBe(dinosaur.id)
    } finally { wrapper.unmount() }
  })
  it('可視STOPPEDはatlasを取得せず同個体staticだけ、静止失敗は文字fallbackで有限終了する', async () => {
    const wrapper = scene('STOPPED')
    try {
      await show()
      expect(images).toHaveLength(0)
      await wrapper.setProps({ active: true })
      expect(images).toHaveLength(1)
      expect(images[0]?.src).toBe('/synthetic/static.png')
      images[0]?.onerror?.()
      await nextTick()
      expect(images).toHaveLength(1)
      expect(wrapper.text()).toContain('Synthetic')
      expect(wrapper.find('canvas').exists()).toBe(false)
      expect(requestAnimationFrame).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })
})
