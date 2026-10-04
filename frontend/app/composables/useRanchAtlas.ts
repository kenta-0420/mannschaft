import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { ranchAtlasFrameRect, type BoundRanchAsset } from '~/utils/ranch-assets'
// 画像は可視後のみ取得する。通常待機frame0を保ち、ここではRAF/timerを作らない。
export function useRanchAtlas(canvas: Ref<HTMLCanvasElement | null>, asset: () => BoundRanchAsset | null, enabled: () => boolean, frame: () => number) {
 const loaded = ref(false); const failed = ref(false)
 let pending: HTMLImageElement | null = null; let decoded: HTMLImageElement | null = null; let generation = 0
 function cancelPending() { if (pending) { pending.onload = null; pending.onerror = null; pending.removeAttribute('src'); pending = null } }
 function paint() {
  const node = canvas.value; const bound = asset()
  if (!node || !decoded || !bound) return
  const rect = ranchAtlasFrameRect(bound.entry, frame())
  const context = node.getContext('2d')
  if (!rect || !context) return
  context.clearRect(0,0,node.width,node.height)
  context.imageSmoothingEnabled = bound.entry.renderStyle !== 'PIXEL'
  context.drawImage(decoded,rect.x,rect.y,rect.width,rect.height,0,0,node.width,node.height)
 }
 function load() {
  const bound = asset()
  if (!enabled() || !bound || decoded || pending) return
  const identity = generation
  const image = new Image(); pending = image
  const current = () => generation === identity && pending === image && asset() === bound && enabled()
  image.onload = () => {
   if (!current()) return
   image.onload = null; image.onerror = null; pending = null
   if (image.naturalWidth !== bound.entry.sourceWidth || image.naturalHeight !== bound.entry.sourceHeight) { failed.value = true; return }
   decoded = image; loaded.value = true; failed.value = false; paint()
  }
  image.onerror = () => { if (!current()) return; cancelPending(); failed.value = true }
  image.src = bound.entry.src
 }
 watch(asset, () => { generation += 1; cancelPending(); decoded = null; loaded.value = false; failed.value = false; load() }, { flush: 'sync', immediate: true })
 watch(enabled, active => { if (!active) { generation += 1; cancelPending() } else { load(); paint() } }, { flush: 'sync' })
 watch([canvas,frame], paint)
 onScopeDispose(() => { generation += 1; cancelPending(); decoded = null })
 return { loaded, failed }
}