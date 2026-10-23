// @vitest-environment happy-dom
import { effectScope, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useRanchClipPlayback } from './useRanchClipPlayback'
import type { RanchDevelopmentClipEntry } from '~/utils/ranch-development-clips'

const callbacks = new Map<number, FrameRequestCallback>()
let nextId = 0
const scopes: ReturnType<typeof effectScope>[] = []
const clip = (durationMs: number) => ({ durationMs, phaseStarts: [0, 0.4, 0.55, 0.9] })
const entry = {
  clips: { idle: clip(1000), walk: clip(800), feed: clip(600), happy: clip(700) },
} as unknown as RanchDevelopmentClipEntry
function setup(initiallyActive = true) {
  const active = ref(initiallyActive),
    ambient = ref(true),
    reduced = ref(false),
    stopped = ref(false),
    ready = ref(true)
  const identity = ref('same-id/BABY/PIXEL')
  const scope = effectScope()
  scopes.push(scope)
  const player = scope.run(() =>
    useRanchClipPlayback({
      entry: () => entry,
      identity: () => identity.value,
      enabled: () => active.value,
      ambient: () => ambient.value,
      reduced: () => reduced.value,
      stopped: () => stopped.value,
      ready: () => ready.value,
    }),
  )!
  return { player, active, ambient, reduced, stopped, ready, identity, scope }
}
function tick(time: number) {
  const pending = [...callbacks.entries()]
  callbacks.clear()
  for (const [, callback] of pending) callback(time)
}
beforeEach(() => {
  callbacks.clear()
  nextId = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    callbacks.set(++nextId, callback)
    return nextId
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => callbacks.delete(id))
})
afterEach(() => {
  for (const scope of scopes.splice(0)) scope.stop()
  vi.unstubAllGlobals()
})

describe('CLIP05/09 native cadence', () => {
  it('idleを2完全cycle後walkを1cycle、phaseStarts境界ちょうどで次frameとなる', () => {
    const { player } = setup()
    tick(0)
    tick(399)
    expect(player.frame.value).toBe(0)
    tick(400)
    expect(player.frame.value).toBe(1)
    tick(1000)
    expect(player.action.value).toBe('idle')
    expect(player.phase.value).toBe(0)
    tick(2000)
    expect(player.action.value).toBe('walk')
    expect(player.phase.value).toBe(0)
    tick(2320)
    expect(player.frame.value).toBe(1)
    tick(2440)
    expect(player.frame.value).toBe(2)
    tick(2720)
    expect(player.frame.value).toBe(3)
    tick(2800)
    expect(player.action.value).toBe('idle')
    expect(player.phase.value).toBe(0)
    expect(callbacks.size).toBe(1)
  })
  it('feed/happyがcadenceを中断し有限終了後idleから2cycleを再開する', () => {
    const { player } = setup()
    tick(0)
    tick(1500)
    expect(player.respond('feed')).toBe(true)
    tick(1600)
    tick(2200)
    expect(player.action.value).toBe('idle')
    tick(3200)
    expect(player.action.value).toBe('idle')
    tick(4200)
    expect(player.action.value).toBe('walk')
  })
})
describe('CLIP07/10/11 cancellation', () => {
  it('初回hiddenはRAF0、scope破棄は稼働中RAFと旧callbackを無効化する', () => {
    const { player, active, scope } = setup(false)
    expect(callbacks.size).toBe(0)
    expect(player.respond('happy')).toBe(false)
    active.value = true
    tick(0)
    const stale = [...callbacks.values()][0]!
    scope.stop()
    expect(callbacks.size).toBe(0)
    stale(100)
    expect(callbacks.size).toBe(0)
  })
  it('PAUSEDのambientはRAFなし、明示happyは許可し終了時に停止する', () => {
    const { player, ambient } = setup()
    ambient.value = false
    expect(callbacks.size).toBe(0)
    expect(player.respond('happy')).toBe(true)
    tick(0)
    tick(700)
    expect(player.action.value).toBe('idle')
    expect(callbacks.size).toBe(0)
  })
  it('非activeは即取消、旧callbackを実行しても新しいactionを消さない', () => {
    const { player, active } = setup()
    tick(0)
    const stale = [...callbacks.values()][0]!
    active.value = false
    expect(callbacks.size).toBe(0)
    active.value = true
    player.respond('happy')
    tick(100)
    stale(99999)
    expect(player.action.value).toBe('happy')
    expect(callbacks.size).toBe(1)
  })
  it('hidden/identity変更後は追いつかずidleから再開しREDUCED/STOPPEDはRAFを作らない', () => {
    const { player, active, identity, reduced, stopped } = setup()
    tick(0)
    tick(1900)
    active.value = false
    active.value = true
    tick(99999)
    expect(player.action.value).toBe('idle')
    expect(player.phase.value).toBe(0)
    player.respond('happy')
    identity.value = 'same-id/ADULT/PIXEL'
    expect(player.action.value).toBe('idle')
    reduced.value = true
    expect(callbacks.size).toBe(0)
    expect(player.respond('happy')).toBe(false)
    reduced.value = false
    stopped.value = true
    expect(callbacks.size).toBe(0)
    expect(player.respond('feed')).toBe(false)
  })
  it('image未取得/失敗中は時計を動かさずready後にnative時刻0で開始する', () => {
    const { player, ready } = setup()
    ready.value = false
    expect(callbacks.size).toBe(0)
    ready.value = true
    tick(50000)
    expect(player.phase.value).toBe(0)
    expect(callbacks.size).toBe(1)
  })
  it('低速feed画像を待つ間にfiniteを消費せずreadyから1cycleを再生する', () => {
    const { player, ready } = setup()
    player.respond('feed')
    ready.value = false
    tick(50000)
    expect(player.action.value).toBe('feed')
    expect(callbacks.size).toBe(0)
    ready.value = true
    tick(60000)
    expect(player.phase.value).toBe(0)
    tick(60599)
    expect(player.action.value).toBe('feed')
    tick(60600)
    expect(player.action.value).toBe('idle')
  })
})
