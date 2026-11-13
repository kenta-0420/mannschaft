// @vitest-environment happy-dom
// 実Sceneと両rendererを通し、画像transport/canvas APIだけを差し替える。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import DinosaurScene from './DinosaurScene.vue'
import { productionRanchAssets } from '~/utils/ranch-production-assets'
import { developmentRanchAssets } from '~/utils/ranch-assets'
import type { DinosaurSummary } from '~/types/ranch'
import ja from '~/locales/ja/ranch.json'
import en from '~/locales/en/ranch.json'
import zh from '~/locales/zh/ranch.json'
import ko from '~/locales/ko/ranch.json'
import es from '~/locales/es/ranch.json'
import de from '~/locales/de/ranch.json'

const entry = productionRanchAssets.find(value => value.speciesKey === 'allosaurus' && value.variantKey === 'V1' && value.stage === 'BABY' && value.renderStyle === 'PIXEL')!
const dinosaur: DinosaurSummary = { id: '11111111-1111-4111-8111-111111111111', speciesKey: entry.speciesKey,
  variantKey: entry.variantKey, speciesCatalogVersion: entry.catalogVersion, stage: 'BABY', habitat: 'LAND',
  name: 'Saved', namedAt: null, xp: '0', nextStageXp: null, version: '1', egg: null }
const images: TestImage[] = []
let visible: IntersectionObserverCallback, osReduced: boolean
class TestImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  naturalWidth = 0
  naturalHeight = 0
  private value = ''
  constructor() { images.push(this) }
  get src() { return this.value }
  set src(value: string) {
    this.value = value
    const clipImage = [entry.clipEntry!.fallback, ...Object.values(entry.clipEntry!.clips)].find(image => image.src === value)
    const legacyImage = developmentRanchAssets.find(image => image.src === value)
    this.naturalWidth = clipImage?.sourceWidth ?? legacyImage?.sourceWidth ?? 0
    this.naturalHeight = clipImage?.sourceHeight ?? legacyImage?.sourceHeight ?? 0
  }
  removeAttribute(name: string) { if (name === 'src') this.src = '' }
}
const context = () => ({ clearRect: vi.fn(), save: vi.fn(), restore: vi.fn(), scale: vi.fn(), drawImage: vi.fn(),
  translate: vi.fn(), rotate: vi.fn(), beginPath: vi.fn(), ellipse: vi.fn(), fill: vi.fn(), moveTo: vi.fn(),
  quadraticCurveTo: vi.fn(), stroke: vi.fn() })
const contexts = new Map<HTMLCanvasElement, ReturnType<typeof context>>()
beforeEach(() => {
  images.length = 0; contexts.clear(); osReduced = false
  vi.stubGlobal('Image', TestImage)
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: IntersectionObserverCallback) { visible = callback }
    observe() {} unobserve() {} disconnect() {}
  })
  vi.stubGlobal('matchMedia', () => ({ matches: osReduced, addEventListener() {}, removeEventListener() {} }))
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function (this: HTMLCanvasElement) {
    if (!contexts.has(this)) contexts.set(this, context())
    return contexts.get(this) as unknown as CanvasRenderingContext2D
  })
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
function scene(motionMode: 'NORMAL' | 'REDUCED') {
  return mount(DinosaurScene, {
    global: { plugins: [createI18n({ legacy: false, locale: 'ja', messages: { ja, en, zh, ko, es, de } })] },
    props: { dinosaur, renderStyle: 'PIXEL', motionMode,
      assetContext: { production: true, isolatedDevelopment: false, publicationEnabled: true } },
  })
}
async function show() {
  visible([{ isIntersecting: true }] as IntersectionObserverEntry[], {} as IntersectionObserver)
  await nextTick()
}
describe('正式v2の単一canvas renderer', () => {
  it('clipを持たないDEV2のlegacy描画を維持する', async () => {
    const legacy = developmentRanchAssets.find(value => value.renderStyle === 'PIXEL')!
    const wrapper = scene('NORMAL')
    try {
      await wrapper.setProps({ dinosaur: { ...dinosaur, speciesKey: legacy.speciesKey, variantKey: legacy.variantKey, speciesCatalogVersion: legacy.catalogVersion },
        assetContext: { production: false, isolatedDevelopment: true, publicationEnabled: false } })
      await show()
      expect(images).toHaveLength(1)
      expect(images[0]?.src).toBe(legacy.src)
      images[0]?.onload?.()
      await nextTick()
      const node = wrapper.get('canvas').element as HTMLCanvasElement
      expect(contexts.get(node)!.drawImage.mock.calls.at(-1)?.slice(-4)).toEqual([0, 0, 96, 96])
    } finally { wrapper.unmount() }
  })
  it('正式clip選択時にはlegacy画像loaderを起動しない', async () => {
    const wrapper = scene('NORMAL')
    try { await show(); expect(images).toHaveLength(1) } finally { wrapper.unmount() }
  })
  it.each(['REDUCED', 'OS_REDUCED'] as const)('%s touchでcanvasを作り直しても影と96cellの配置を保持する', async mode => {
    osReduced = mode === 'OS_REDUCED'
    const wrapper = scene(mode === 'REDUCED' ? 'REDUCED' : 'NORMAL')
    try {
      await show()
      for (const image of [...images]) image.onload?.()
      await nextTick()
      const previous = wrapper.get('canvas').element
      await wrapper.setProps({ reaction: { key: 'DINOSAUR_TOUCH', dinosaurId: dinosaur.id, sequence: 1 } })
      await nextTick()
      const node = wrapper.get('canvas').element as HTMLCanvasElement
      expect(node).not.toBe(previous)
      const ctx = contexts.get(node)!
      expect(ctx.drawImage.mock.calls.at(-1)?.slice(-4)).toEqual([8, 8, 96, 96])
      expect(ctx.ellipse).toHaveBeenCalled()
      expect(requestAnimationFrame).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })
})
