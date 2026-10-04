import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { ranchAtlasFrameRect, type BoundRanchAsset } from '~/utils/ranch-assets'
// 画像は可視後のみ取得する。通常待機frame0を保ち、ここではRAF/timerを作らない。
export function useRanchAtlas(canvas: Ref<HTMLCanvasElement | null>, asset: () => BoundRanchAsset | null, enabled: () => boolean, frame: () => number, staticOnly: () => boolean = () => false) {
 const loaded = ref(false); const failed = ref(false); const usingFallback = ref(false)
 let pending: HTMLImageElement | null = null; let decoded: HTMLImageElement | null = null; let generation = 0
 function cancelPending() { if (pending) { pending.onload = null; pending.onerror = null; pending.removeAttribute('src'); pending = null } }
 function paint() {
  const node = canvas.value; const bound = asset()
  if (!node || !decoded || !bound) return
  const rect = usingFallback.value ? { x: 0, y: 0, width: decoded.naturalWidth, height: decoded.naturalHeight } : ranchAtlasFrameRect(bound.entry, frame())
  const context = node.getContext('2d')
  if (!rect || !context) return
  context.clearRect(0,0,node.width,node.height)
  context.imageSmoothingEnabled = bound.entry.renderStyle !== 'PIXEL'
  context.drawImage(decoded,rect.x,rect.y,rect.width,rect.height,0,0,node.width,node.height)
 }
 function load(fallback = false) {
  const bound = asset()
  if (!enabled() || !bound || decoded || pending || failed.value) return
  const fallbackSrc = bound.entry.fallbackSrc
  const useFallback = !!fallbackSrc && (fallback || staticOnly())
  const identity = generation
  const image = new Image(); pending = image
  const current = () => generation === identity && pending === image && asset() === bound && enabled()
  image.onload = () => {
   if (!current()) return
   image.onload = null; image.onerror = null; pending = null
   const expected = useFallback ? ranchAtlasFrameRect(bound.entry, 0) : { width: bound.entry.sourceWidth, height: bound.entry.sourceHeight }
   if (!expected || image.naturalWidth !== expected.width || image.naturalHeight !== expected.height) {
    if (!useFallback && bound.entry.fallbackSrc) load(true)
    else failed.value = true
    return
   }
   decoded = image; usingFallback.value = useFallback; loaded.value = true; failed.value = false; paint()
  }
  image.onerror = () => {
   if (!current()) return
   cancelPending()
   if (!useFallback && bound.entry.fallbackSrc) load(true)
   else failed.value = true
  }
  // srcは検証済みの有限entryのみ。静止画像の失敗からatlasへ逆戻りしない。
  image.src = useFallback && fallbackSrc ? fallbackSrc : bound.entry.src
 }
 watch([asset,staticOnly], () => { generation += 1; cancelPending(); decoded = null; loaded.value = false; failed.value = false; usingFallback.value = false; load() }, { flush: 'sync', immediate: true })
 watch(enabled, active => { if (!active) { generation += 1; cancelPending() } else { load(); paint() } }, { flush: 'sync' })
 watch([canvas,frame], paint)
 onScopeDispose(() => { generation += 1; cancelPending(); decoded = null })
 return { loaded, failed, usingFallback }
}
