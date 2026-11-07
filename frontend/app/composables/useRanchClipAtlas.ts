import { onScopeDispose, ref, watch, type Ref } from 'vue'
import type {
  RanchClipAction,
  RanchClipImage,
  RanchDevelopmentClipEntry,
} from '~/utils/ranch-development-clips'
export interface RanchClipAtlasOptions {
  entry: () => RanchDevelopmentClipEntry | null
  identity: () => string
  enabled: () => boolean
  action: () => RanchClipAction
  request?: () => number
  phase: () => number
  frame: () => number
  staticOnly: () => boolean
}
// native HTMLの描画式を移植。画像cellには元crop/配置が既に反映されている。
export function paintRanchClip(
  node: HTMLCanvasElement,
  image: HTMLImageElement,
  entry: RanchDevelopmentClipEntry,
  action: RanchClipAction,
  frame: number,
  phase: number,
  fallback: boolean,
) {
  const context = node.getContext('2d')
  const clip = entry.clips[action]
  if (!context || (!fallback && (!clip || frame < 0 || frame >= clip.frames))) return
  const factor = entry.renderStyle === 'PIXEL' ? 1 : 4
  const rect = entry.careSpriteRect
  context.clearRect(0, 0, node.width, node.height)
  context.save()
  context.scale(factor, factor)
  context.imageSmoothingEnabled = entry.renderStyle === 'PAINT_2D'
  if (entry.habitat === 'LAND') {
    context.fillStyle = '#c4cda8'
    context.beginPath()
    context.ellipse(54, 97, 22, 1.5, 0, 0, Math.PI * 2)
    context.fill()
  }
  context.save()
  if (!fallback && action === 'happy' && entry.habitat === 'SEA') {
    const angles = [0, -4, -7, -4, 0, 4, 7, 4, 0, -3, 0, 0, 0]
    const offsets = [0, -1, -2, -1, 0, 1, 2, 1, 0, -1, 0, 0, 0]
    const step = Math.min(11, Math.floor(phase * 12))
    const mix = entry.renderStyle === 'PIXEL' ? 0 : phase * 12 - step
    const angle = (angles[step] ?? 0) * (1 - mix) + (angles[step + 1] ?? 0) * mix
    const offset = (offsets[step] ?? 0) * (1 - mix) + (offsets[step + 1] ?? 0) * mix
    context.translate(56 + offset, 59)
    context.rotate((angle * Math.PI) / 180)
    context.translate(-56, -59)
  } else if (!fallback && action === 'happy' && entry.habitat === 'AIR') {
    const sway = Math.sin(phase * Math.PI * 4) * Math.sin(phase * Math.PI)
    context.translate(entry.renderStyle === 'PIXEL' ? Math.round(sway) : sway, 0)
  }
  const floor = clip?.sourceFloorByFrame384?.[frame] ?? clip?.sourceFloor384
  const y =
    !fallback &&
    action === 'walk' &&
    entry.habitat === 'LAND' &&
    floor !== null &&
    floor !== undefined
      ? 97 - (floor / 384) * 96
      : rect.y
  const cell = fallback ? image.naturalWidth : clip!.cell
  context.drawImage(
    image,
    fallback ? 0 : frame * cell,
    0,
    cell,
    fallback ? image.naturalHeight : cell,
    rect.x,
    y,
    rect.width,
    rect.height,
  )
  context.restore()
  if (!fallback && action === 'happy' && entry.habitat === 'LAND') {
    const mark = clip?.tailMarks?.[frame]
    if (mark) {
      const t = (phase * 4) % 1
      context.globalAlpha = Math.max(0, 1 - t / 0.55) * 0.65
      context.strokeStyle = '#775a36'
      context.lineWidth = 0.85
      context.lineCap = 'round'
      for (let index = 0; index < 3; index += 1) {
        const x = mark.x + 8 + index * 2.5,
          y = mark.y + 8 - index * 1.3
        context.beginPath()
        context.moveTo(x, y)
        context.quadraticCurveTo(x + 2.5, y + 4, x, y + 8)
        context.stroke()
      }
    }
  }
  context.restore()
}

export function useRanchClipAtlas(
  canvas: Ref<HTMLCanvasElement | null>,
  options: RanchClipAtlasOptions,
) {
  const loaded = ref(false),
    failed = ref(false),
    usingFallback = ref(false)
  // sceneの同一identityだけ。4action+fallbackの最大5画像、bulk/eager取得なし。
  const cache = new Map<string, HTMLImageElement>()
  let pending: HTMLImageElement | null = null,
    decoded: HTMLImageElement | null = null
  let generation = 0
  function cancelPending() {
    generation += 1
    if (pending) {
      pending.onload = null
      pending.onerror = null
      pending.removeAttribute('src')
      pending = null
    }
  }
  function paint() {
    const entry = options.entry(),
      node = canvas.value
    if (options.enabled() && entry && node && decoded)
      paintRanchClip(
        node,
        decoded,
        entry,
        options.action(),
        options.frame(),
        options.phase(),
        usingFallback.value,
      )
  }
  function acquire(forceFallback = false) {
    const entry = options.entry()
    if (!options.enabled() || !entry || pending || failed.value) return
    const clip = entry.clips[options.action()]
    const fallback = forceFallback || options.staticOnly() || !clip
    const asset: RanchClipImage = fallback ? entry.fallback : clip
    const cached = cache.get(asset.src)
    if (cached) {
      decoded = cached
      usingFallback.value = fallback
      loaded.value = true
      paint()
      return
    }
    const identity = generation
    const image = new Image()
    pending = image
    const current = () =>
      identity === generation && pending === image && options.enabled() && options.entry() === entry
    function fail() {
      if (!current()) return
      cancelPending()
      if (!fallback) acquire(true)
      else failed.value = true
    }
    function complete() {
      if (!current()) return
      if (image.naturalWidth !== asset.sourceWidth || image.naturalHeight !== asset.sourceHeight) {
        fail()
        return
      }
      image.onload = null
      image.onerror = null
      pending = null
      cache.set(asset.src, image)
      decoded = image
      usingFallback.value = fallback
      loaded.value = true
      paint()
    }
    image.onload = () => {
      if (!current()) return
      // decode中もpendingを保持し、hidden/action切替で同じ世代を無効化する。
      if (typeof image.decode === 'function') void image.decode().then(complete, fail)
      else complete()
    }
    image.onerror = fail
    image.src = asset.src
  }
  function reset() {
    cancelPending()
    decoded = null
    loaded.value = false
    failed.value = false
    usingFallback.value = false
    acquire()
  }
  watch(
    [options.entry, options.identity],
    () => {
      cache.clear()
      reset()
    },
    { flush: 'sync', immediate: true },
  )
  watch([options.action, options.staticOnly, options.request ?? (() => 0)], reset, {
    flush: 'sync',
  })
  watch(
    options.enabled,
    (active) => {
      if (!active) cancelPending()
      else {
        if (!decoded) acquire()
        paint()
      }
    },
    { flush: 'sync' },
  )
  watch([canvas, options.frame, options.phase], paint, { flush: 'sync' })
  onScopeDispose(() => {
    cancelPending()
    decoded = null
    cache.clear()
  })
  return { loaded, failed, usingFallback }
}
