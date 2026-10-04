import { onScopeDispose, watch } from 'vue'
import type { DinosaurSummary, MotionMode } from '~/types/ranch'
import { resolveRanchReaction } from '~/utils/ranch-assets'
// 固定の短い音だけを本人操作に結び付ける。素材URLや定期再生は作らない。
export function useRanchTouchSound(options: {
 dinosaur: () => DinosaurSummary
 enabled: () => boolean
 motion: () => MotionMode
 soundEnabled: () => boolean
 volume: () => number
}) {
 let generation = 0
 let prepared: { token: number; dinosaurId: string; context: AudioContext; oscillator: OscillatorNode | null; gain: GainNode | null } | null = null
 const allowed = () => options.enabled() && options.motion() !== 'STOPPED' && options.soundEnabled() && options.volume() > 0
 function stop() {
  generation += 1
  const previous = prepared
  prepared = null
  if (!previous) return
  if (previous.oscillator) { previous.oscillator.onended = null; try { previous.oscillator.stop() } catch { /* 自然終了済み */ } previous.oscillator.disconnect() }
  previous.gain?.disconnect()
  void previous.context.close().catch(() => {})
 }
 function prepare() {
  stop()
  if (!allowed() || typeof window.AudioContext !== 'function') return null
  const context = new window.AudioContext()
  const token = generation
  prepared = { token, dinosaurId: options.dinosaur().id, context, oscillator: null, gain: null }
  void context.resume().catch(() => { if (prepared?.token === token) stop() })
  return token
 }
 function cancel(token: number | null) { if (token !== null && prepared?.token === token) stop() }
 function play(key: string, dinosaurId: string, token: number | null) {
  const run = prepared
  if (!run || token === null || run.token !== token || !allowed() || dinosaurId !== run.dinosaurId || dinosaurId !== options.dinosaur().id || !resolveRanchReaction(key, options.dinosaur().stage)) { cancel(token); return }
  if (run.oscillator) return // 同じ操作tokenの再通知では再生しない。
  const oscillator = run.context.createOscillator()
  const gain = run.context.createGain()
  run.oscillator = oscillator; run.gain = gain
  const now = run.context.currentTime
  gain.gain.setValueAtTime(Math.min(100, Math.max(0, options.volume())) / 100 * 0.04, now)
  gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.09)
  oscillator.frequency.setValueAtTime(key === 'EGG_TOUCH' ? 440 : 330, now)
  oscillator.connect(gain); gain.connect(run.context.destination)
  oscillator.onended = () => { if (prepared === run) stop() }
  oscillator.start(now); oscillator.stop(now + 0.1)
 }
 watch([options.enabled, options.motion, options.soundEnabled, options.volume, () => options.dinosaur().id, () => options.dinosaur().stage], stop, { flush: 'sync' })
 onScopeDispose(stop)
 return { prepare, play, cancel }
}
