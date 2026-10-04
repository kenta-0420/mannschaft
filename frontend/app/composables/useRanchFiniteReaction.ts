// 通常待機はframe0固定。保存済みTOUCH応答だけを有限の反応へ接続する。
import { computed, onScopeDispose, ref, watch } from 'vue'
import type { DinosaurSummary, MotionMode, RenderStyle } from '~/types/ranch'
import { resolveRanchReaction } from '~/utils/ranch-assets'

export function useRanchFiniteReaction(options: {
 dinosaur: () => DinosaurSummary
 motion: () => MotionMode
 renderStyle: () => RenderStyle
 contextEnabled: () => boolean
 osReduced: () => boolean
}) {
 const reaction = ref<'EGG_TOUCH' | 'DINOSAUR_TOUCH' | null>(null)
 const progress = ref(0)
 const sequence = ref(0)
 let frame: number | null = null
 let run = 0
 let startedAt: number | null = null
 const reduced = computed(() => options.motion() === 'REDUCED' || options.osReduced())
 const allowed = () => options.contextEnabled() && options.motion() !== 'STOPPED'
 function stop() {
  run += 1
  if (frame !== null) cancelAnimationFrame(frame)
  frame = null; startedAt = null; reaction.value = null; progress.value = 0
 }
 // CSSだけの単発色応答はanimationendで終了。disabled時にはwatchから即clearする。
 function finishReduced(event: Event) {
  if (!reduced.value || !(event.currentTarget instanceof HTMLElement)) return
  // 各keyed DOMの固定datasetを読む。旧nodeの終了eventで新sequenceを消さない。
  if (event.currentTarget.dataset.reactionSequence !== String(sequence.value)) return
  stop()
 }
 function respond(key: string, dinosaurId: string) {
  stop()
  const d = options.dinosaur()
  const effect = resolveRanchReaction(key, d.stage)
  if (!allowed() || dinosaurId !== d.id || !effect) return false
  sequence.value += 1
  reaction.value = key === 'EGG_TOUCH' ? 'EGG_TOUCH' : 'DINOSAUR_TOUCH'
  if (reduced.value) return true // RAF/timerなし。移動せずCSS色応答だけ。
  const identity = run
  const tick = (time: number) => {
   if (identity !== run || !allowed() || options.dinosaur().id !== dinosaurId || reduced.value) { stop(); return }
   startedAt ??= time
   const elapsed = time - startedAt
   if (elapsed >= effect.durationMs) { stop(); return }
   progress.value = elapsed / effect.durationMs
   frame = requestAnimationFrame(tick)
  }
  frame = requestAnimationFrame(tick)
  return true
 }
 watch([options.contextEnabled, options.motion, options.renderStyle, options.osReduced, () => options.dinosaur().id, () => options.dinosaur().stage], stop, { flush: 'sync' })
 onScopeDispose(stop)
 // 許可manifestの8framesにだけ接続。通常/終了時は0。同ID/stage/styleが変われば即stop。
 const pilotFrame = computed(() => reaction.value === 'DINOSAUR_TOUCH' && !reduced.value ? Math.min(7, Math.floor(progress.value * 8)) : 0)
 return { reaction, progress, sequence, reduced, pilotFrame, respond, stop, finishReduced }
}

// SVG crackはserver crackStageだけを参照（経過時間catchupなし）。
export const eggCrackPaths = Object.freeze({
 INTACT: [],
 SMALL_CRACK: ['M42 27L47 35L42 43L50 48'],
 WIDE_CRACK: ['M40 23L48 34L39 43L51 52L43 62', 'M62 38L54 46L61 56'],
 READY: ['M35 21L47 34L37 44L54 55L41 67L51 78', 'M66 31L55 45L66 57L54 68'],
} as const)