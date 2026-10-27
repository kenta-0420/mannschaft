import { computed, onScopeDispose, ref, watch } from 'vue'
import type { RanchClipAction, RanchDevelopmentClipEntry } from '~/utils/ranch-development-clips'

export interface RanchClipPlaybackOptions {
  entry: () => RanchDevelopmentClipEntry | null
  identity: () => string
  enabled: () => boolean
  ambient: () => boolean
  reduced: () => boolean
  stopped: () => boolean
  ready: () => boolean
}

// v3専用。legacyの静止待機/8コマTOUCHには接続しない。
export function useRanchClipPlayback(options: RanchClipPlaybackOptions) {
  const action = ref<RanchClipAction>('idle')
  const request = ref(0)
  const phase = ref(0)
  const frame = computed(() => {
    const starts = options.entry()?.clips[action.value]?.phaseStarts
    if (!starts) return 0
    let value = 0
    while (value < starts.length - 1 && phase.value >= (starts[value + 1] ?? 1)) value += 1
    return value
  })
  let pending: number | null = null
  let generation = 0
  let startedAt: number | null = null
  let idleCycles = 0
  let finite = false
  let disposed = false
  const allowed = () =>
    !disposed && !!options.entry() && options.enabled() && !options.reduced() && !options.stopped()
  const running = () => allowed() && options.ready() && (finite || options.ambient())
  function cancelClock() {
    generation += 1
    if (pending !== null) cancelAnimationFrame(pending)
    pending = null
    startedAt = null
  }
  function schedule() {
    if (pending !== null || !running()) return
    const identity = generation
    pending = requestAnimationFrame((time) => tick(time, identity))
  }
  function reset() {
    cancelClock()
    finite = false
    idleCycles = 0
    action.value = 'idle'
    phase.value = 0
    schedule()
  }
  function tick(time: number, identity: number) {
    // 古いcallbackは現在のRAF識別子やactionにも触れない。
    if (identity !== generation || !running()) return
    pending = null
    startedAt ??= time
    const clip = options.entry()?.clips[action.value]
    if (!clip || clip.durationMs <= 0) {
      cancelClock()
      return
    }
    let elapsed = Math.max(0, time - startedAt)
    if (elapsed >= clip.durationMs) {
      if (action.value === 'idle' && ++idleCycles < 2) {
        startedAt += clip.durationMs
        elapsed -= clip.durationMs
        // 可視中に低fpsでもidle2回を超えてwalk自体を飛ばさない。
        if (elapsed >= clip.durationMs) {
          idleCycles = 2
          action.value = 'walk'
          phase.value = 0
          startedAt = time
        } else phase.value = elapsed / clip.durationMs
      } else {
        const completed = action.value
        if (completed !== 'idle') {
          finite = false
          idleCycles = 0
        }
        action.value = completed === 'idle' ? 'walk' : 'idle'
        phase.value = 0
        startedAt = time
      }
    } else phase.value = elapsed / clip.durationMs
    schedule()
  }
  function respond(next: 'feed' | 'happy') {
    if (!allowed()) return false
    cancelClock()
    finite = true
    idleCycles = 0
    phase.value = 0
    if (action.value === next) request.value += 1
    action.value = next
    schedule()
    return true
  }
  watch(
    [options.entry, options.identity, options.enabled, options.reduced, options.stopped],
    reset,
    { flush: 'sync', immediate: true },
  )
  watch(
    options.ambient,
    () => {
      if (!finite) reset()
    },
    { flush: 'sync' },
  )
  // 画像待機は有限actionを保持する。非表示/identity変更は上のwatchが全破棄する。
  watch(
    options.ready,
    () => {
      cancelClock()
      phase.value = 0
      schedule()
    },
    { flush: 'sync' },
  )
  onScopeDispose(() => {
    disposed = true
    cancelClock()
  })
  return { action, phase, frame, request, respond }
}
