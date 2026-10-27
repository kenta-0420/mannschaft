// @vitest-environment happy-dom
import { effectScope, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { paintRanchClip, useRanchClipAtlas } from './useRanchClipAtlas'
import type { RanchDevelopmentClipEntry } from '~/utils/ranch-development-clips'

const clip = (action: string) => ({
  action,
  src: '/' + action + '.png',
  sourceWidth: 384,
  sourceHeight: 96,
  cell: 96,
  frames: 4,
  durationMs: 1000,
  phaseStarts: [0, 0.25, 0.5, 0.75],
  sourceFloor384: 356,
  sourceFloorByFrame384: [null, 352, null, null],
  tailMarks: [
    { x: 20, y: 30 },
    { x: 22, y: 31 },
    { x: 24, y: 32 },
    { x: 26, y: 33 },
  ],
})
const entry = {
  renderStyle: 'PIXEL',
  habitat: 'LAND',
  logicalCanvas: { width: 112, height: 112 },
  careSpriteRect: { x: 8, y: 8, width: 96, height: 96 },
  fallback: { src: '/fallback.png', sourceWidth: 96, sourceHeight: 96 },
  clips: { idle: clip('idle'), walk: clip('walk'), feed: clip('feed'), happy: clip('happy') },
} as unknown as RanchDevelopmentClipEntry
const images: TestImage[] = []
const scopes: ReturnType<typeof effectScope>[] = []
class TestImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  src = ''
  naturalWidth = 384
  naturalHeight = 96
  constructor() {
    images.push(this)
  }
  removeAttribute(name: string) {
    if (name === 'src') this.src = ''
  }
}
function context() {
  return {
    clearRect: vi.fn(),
    save: vi.fn(),
    restore: vi.fn(),
    scale: vi.fn(),
    drawImage: vi.fn(),
    translate: vi.fn(),
    rotate: vi.fn(),
    beginPath: vi.fn(),
    ellipse: vi.fn(),
    fill: vi.fn(),
    moveTo: vi.fn(),
    quadraticCurveTo: vi.fn(),
    stroke: vi.fn(),
  }
}
function setup() {
  const request = ref(0)
  const enabled = ref(false),
    action = ref<'idle' | 'walk' | 'feed' | 'happy'>('idle'),
    phase = ref(0),
    frame = ref(0),
    staticOnly = ref(false)
  const bound = ref(entry),
    identity = ref('individual-1/BABY/PIXEL'),
    node = document.createElement('canvas'),
    ctx = context()
  node.width = node.height = 112
  vi.spyOn(node, 'getContext').mockReturnValue(ctx as unknown as CanvasRenderingContext2D)
  const canvas = ref<HTMLCanvasElement | null>(node),
    scope = effectScope()
  scopes.push(scope)
  const atlas = scope.run(() =>
    useRanchClipAtlas(canvas, {
      entry: () => bound.value,
      identity: () => identity.value,
      enabled: () => enabled.value,
      action: () => action.value,
      request: () => request.value,
      phase: () => phase.value,
      frame: () => frame.value,
      staticOnly: () => staticOnly.value,
    }),
  )!
  return { atlas, enabled, action, request, phase, frame, staticOnly, bound, identity, ctx }
}
beforeEach(() => {
  images.length = 0
  vi.stubGlobal('Image', TestImage)
})
afterEach(() => {
  for (const scope of scopes.splice(0)) scope.stop()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
describe('CLIP10/12/14 clip image transport', () => {
  it('failed actionはstatic fallbackで止まり次の明示同actionだけでprimaryを再試行する', () => {
    const { enabled, action, request, atlas, ctx } = setup()
    action.value = 'walk'
    enabled.value = true
    images[0]!.onerror?.()
    images[1]!.naturalWidth = 96
    images[1]!.onload?.()
    expect(atlas.usingFallback.value).toBe(true)
    expect(images).toHaveLength(2)
    expect(ctx.drawImage).toHaveBeenLastCalledWith(images[1], 0, 0, 96, 96, 8, 8, 96, 96)
    request.value += 1
    expect(images).toHaveLength(3)
    expect(images[2]!.src).toBe('/walk.png')
  })
  it('初回不可視は0取得、低速action画像はready待機しhiddenでpending/旧callbackを取消する', () => {
    const { enabled, action, atlas, ctx } = setup()
    expect(images).toHaveLength(0)
    enabled.value = true
    expect(images).toHaveLength(1)
    images[0]!.onload?.()
    expect(atlas.loaded.value).toBe(true)
    action.value = 'feed'
    expect(atlas.loaded.value).toBe(false)
    expect(images).toHaveLength(2)
    const late = images[1]!.onload!
    enabled.value = false
    expect(images[1]!.onload).toBeNull()
    expect(images[1]!.src).toBe('')
    ctx.drawImage.mockClear()
    late()
    expect(ctx.drawImage).not.toHaveBeenCalled()
  })
  it('action切替の旧画像は新actionをpaintせず、同entry idleはcache再利用する', () => {
    const { enabled, action, atlas, ctx } = setup()
    enabled.value = true
    images[0]!.onload?.()
    action.value = 'feed'
    const late = images[1]!.onload!
    action.value = 'happy'
    expect(images).toHaveLength(3)
    late()
    expect(atlas.loaded.value).toBe(false)
    images[2]!.onload?.()
    expect(atlas.loaded.value).toBe(true)
    action.value = 'idle'
    expect(images).toHaveLength(3)
    expect(atlas.loaded.value).toBe(true)
    expect(ctx.drawImage).toHaveBeenLastCalledWith(images[0], 0, 0, 96, 96, 8, 8, 96, 96)
  })
  it('寸法不正は同entry fallbackだけへ移行しfallback失敗はassetPreparingとなる', () => {
    const { enabled, atlas } = setup()
    enabled.value = true
    images[0]!.naturalWidth = 95
    images[0]!.onload?.()
    expect(images).toHaveLength(2)
    expect(images[1]!.src).toBe('/fallback.png')
    images[1]!.onerror?.()
    expect(atlas.failed.value).toBe(true)
    expect(images).toHaveLength(2)
  })
  it('同entryでもindividual変更はpending世代を破棄し旧onloadが新個体を描かない', () => {
    const { enabled, identity, atlas, ctx } = setup()
    enabled.value = true
    const late = images[0]!.onload!
    identity.value = 'individual-2/BABY/PIXEL'
    expect(images).toHaveLength(2)
    late()
    expect(atlas.loaded.value).toBe(false)
    expect(ctx.drawImage).not.toHaveBeenCalled()
    images[1]!.onload?.()
    expect(atlas.loaded.value).toBe(true)
  })
  it('要求action欠損は別actionを取らずsame-entry静止だけを取得する', () => {
    const { enabled, bound, action, atlas } = setup()
    bound.value = {
      ...entry,
      clips: { idle: entry.clips.idle, walk: entry.clips.walk, happy: entry.clips.happy },
    } as unknown as RanchDevelopmentClipEntry
    action.value = 'feed'
    enabled.value = true
    expect(images).toHaveLength(1)
    expect(images[0]!.src).toBe('/fallback.png')
    images[0]!.naturalWidth = 96
    images[0]!.onload?.()
    expect(atlas.usingFallback.value).toBe(true)
  })
  it('decode失敗はsame-entry fallbackへ移行し古decode完了は新actionをpaintしない', async () => {
    const { enabled, action, atlas, ctx } = setup()
    enabled.value = true
    Object.assign(images[0]!, { decode: () => Promise.reject(new Error('DECODE_FAILED')) })
    images[0]!.onload?.()
    await flushPromises()
    expect(images[1]!.src).toBe('/fallback.png')
    action.value = 'happy'
    let complete: (() => void) | undefined
    Object.assign(images[2]!, {
      decode: () =>
        new Promise<void>((resolve) => {
          complete = resolve
        }),
    })
    images[2]!.onload?.()
    action.value = 'feed'
    ctx.drawImage.mockClear()
    complete?.()
    await flushPromises()
    expect(atlas.loaded.value).toBe(false)
    expect(ctx.drawImage).not.toHaveBeenCalled()
  })
  it('STOPPED/REDUCEDは同entry静止だけ取得、切替は進行中atlasを直ちに取消する', () => {
    const { enabled, staticOnly, atlas } = setup()
    staticOnly.value = true
    enabled.value = true
    expect(images[0]!.src).toBe('/fallback.png')
    images[0]!.naturalWidth = 96
    images[0]!.onload?.()
    expect(atlas.usingFallback.value).toBe(true)
    staticOnly.value = false
    expect(images[1]!.src).toBe('/idle.png')
    staticOnly.value = true
    expect(images[1]!.src).toBe('')
    expect(images).toHaveLength(2)
  })
})
describe('CLIP08/09 native paint', () => {
  it('LAND walkは4frameのsource384 floorを優先し96rectを112canvasに配置する', () => {
    const ctx = context(),
      image = new TestImage(),
      node = { width: 112, height: 112, getContext: () => ctx } as unknown as HTMLCanvasElement
    paintRanchClip(node, image as unknown as HTMLImageElement, entry, 'walk', 1, 0.4, false)
    expect(ctx.drawImage).toHaveBeenCalledWith(image, 96, 0, 96, 96, 8, 9, 96, 96)
    paintRanchClip(node, image as unknown as HTMLImageElement, entry, 'walk', 0, 0, false)
    expect(ctx.drawImage).toHaveBeenLastCalledWith(image, 0, 0, 96, 96, 8, 8, 96, 96)
  })
  it('LAND happyはmetadata座標から3本のtailMarksだけを描きgenericbounceしない', () => {
    const ctx = context(),
      image = new TestImage(),
      node = { width: 112, height: 112, getContext: () => ctx } as unknown as HTMLCanvasElement
    paintRanchClip(node, image as unknown as HTMLImageElement, entry, 'happy', 2, 0.5, false)
    expect(ctx.moveTo.mock.calls).toEqual([
      [32, 40],
      [34.5, 38.7],
      [37, 37.4],
    ])
    expect(ctx.quadraticCurveTo).toHaveBeenCalledTimes(3)
    expect(ctx.translate).not.toHaveBeenCalled()
    expect(ctx.drawImage).toHaveBeenCalledWith(image, 192, 0, 96, 96, 8, 8, 96, 96)
  })
  it('SEA rollはPIXEL step保持/2D補間、AIR swayはPIXEL整数/2D連続値となる', () => {
    const ctx = context(),
      image = new TestImage(),
      node = { width: 112, height: 112, getContext: () => ctx } as unknown as HTMLCanvasElement
    paintRanchClip(
      node,
      image as unknown as HTMLImageElement,
      { ...entry, habitat: 'SEA' },
      'happy',
      0,
      0.125,
      false,
    )
    expect(ctx.rotate).toHaveBeenLastCalledWith((-4 * Math.PI) / 180)
    paintRanchClip(
      node,
      image as unknown as HTMLImageElement,
      { ...entry, habitat: 'SEA', renderStyle: 'PAINT_2D' },
      'happy',
      0,
      0.125,
      false,
    )
    expect(ctx.rotate).toHaveBeenLastCalledWith((-5.5 * Math.PI) / 180)
    ctx.translate.mockClear()
    paintRanchClip(
      node,
      image as unknown as HTMLImageElement,
      { ...entry, habitat: 'AIR' },
      'happy',
      0,
      0.125,
      false,
    )
    expect(ctx.translate).toHaveBeenLastCalledWith(0, 0)
    paintRanchClip(
      node,
      image as unknown as HTMLImageElement,
      { ...entry, habitat: 'AIR', renderStyle: 'PAINT_2D' },
      'happy',
      0,
      0.125,
      false,
    )
    expect(ctx.translate).toHaveBeenLastCalledWith(Math.sin(Math.PI / 2) * Math.sin(Math.PI / 8), 0)
  })
})
