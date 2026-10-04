// @vitest-environment happy-dom
import { effectScope, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useRanchTouchSound } from './useRanchTouchSound'
import type { DinosaurSummary, RenderStyle } from '~/types/ranch'
const dinosaur: DinosaurSummary = { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', speciesKey: null, variantKey: null, speciesCatalogVersion: null, stage: 'BABY', habitat: 'LAND', name: null, namedAt: null, xp: '0', nextStageXp: null, version: '0', egg: null }
afterEach(() => vi.unstubAllGlobals())
describe('操作起点だけの短い音', () => {
 it('初期OFF/非active/STOPPEDはAudioContextを生成せず、旧音終了が次操作を止めない', () => {
  const contexts: AudioFixture[] = []
  class AudioFixture {
   currentTime = 0
   destination = {}
   close = vi.fn(async () => {})
   resume = vi.fn(async () => {})
   oscillator = { onended: null as (() => void) | null, stop: vi.fn(), disconnect: vi.fn(), frequency: { setValueAtTime: vi.fn() }, connect: vi.fn(), start: vi.fn() }
   constructor() { contexts.push(this) }
   createOscillator() { return this.oscillator }
   createGain() { return { gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() }, connect: vi.fn(), disconnect: vi.fn() } }
  }
  vi.stubGlobal('AudioContext', AudioFixture)
  const renderStyle = ref<RenderStyle>('PIXEL')
  const soundEnabled = ref(false); const enabled = ref(true); const motion = ref<'NORMAL' | 'STOPPED'>('NORMAL')
  const scope = effectScope()
  const sound = scope.run(() => useRanchTouchSound({ dinosaur: () => dinosaur, enabled: () => enabled.value, motion: () => motion.value, renderStyle: () => renderStyle.value, soundEnabled: () => soundEnabled.value, volume: () => 50 }))
  if (!sound) throw new Error('SOUND_SCOPE_MISSING')
  expect(sound.prepare()).toBeNull(); expect(contexts).toHaveLength(0)
  soundEnabled.value = true; enabled.value = false
  expect(sound.prepare()).toBeNull()
  enabled.value = true; motion.value = 'STOPPED'
  expect(sound.prepare()).toBeNull(); expect(contexts).toHaveLength(0)
  motion.value = 'NORMAL'
  const a = sound.prepare(); sound.play('DINOSAUR_TOUCH', dinosaur.id, a)
  const first = contexts[0]; if (!first?.oscillator.onended) throw new Error('FIRST_AUDIO_MISSING')
  const oldEnded = first.oscillator.onended
  const b = sound.prepare(); sound.play('DINOSAUR_TOUCH', dinosaur.id, b)
  const second = contexts[1]; if (!second) throw new Error('SECOND_AUDIO_MISSING')
  oldEnded(); expect(second.close).not.toHaveBeenCalled()
  renderStyle.value = 'PAINT_2D'; expect(second.close).toHaveBeenCalledOnce()
  const c = sound.prepare(); sound.play('DINOSAUR_TOUCH', dinosaur.id, c)
  const third = contexts[2]; if (!third) throw new Error('THIRD_AUDIO_MISSING')
  enabled.value = false; expect(third.close).toHaveBeenCalledOnce()
  scope.stop()
 })
 it('resume/oscillator/closeの失敗を外へthrowせず資源終了を試す', () => {
  const closed = vi.fn(() => { throw new Error('SYNTHETIC_CLOSE_FAILURE') })
  class ResumeFailure {
   close = closed
   resume() { throw new Error('SYNTHETIC_RESUME_FAILURE') }
  }
  vi.stubGlobal('AudioContext', ResumeFailure)
  const scope = effectScope()
  const sound = scope.run(() => useRanchTouchSound({ dinosaur: () => dinosaur, enabled: () => true, motion: () => 'NORMAL', renderStyle: () => 'PIXEL', soundEnabled: () => true, volume: () => 50 }))
  if (!sound) throw new Error('SOUND_SCOPE_MISSING')
  expect(sound.prepare()).toBeNull(); expect(closed).toHaveBeenCalledOnce()
  class OscillatorFailure {
   close = closed
   resume = vi.fn(async () => {})
   createOscillator() { throw new Error('SYNTHETIC_OSCILLATOR_FAILURE') }
  }
  vi.stubGlobal('AudioContext', OscillatorFailure)
  const token = sound.prepare()
  expect(token).not.toBeNull()
  expect(() => sound.play('DINOSAUR_TOUCH', dinosaur.id, token)).not.toThrow()
  expect(closed).toHaveBeenCalledTimes(2); expect(() => scope.stop()).not.toThrow()
 })

})
