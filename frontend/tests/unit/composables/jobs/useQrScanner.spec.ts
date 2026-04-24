import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { pulseVibration, playBeep } from '~/composables/jobs/useQrScanner'

/**
 * F13.1 Phase 13.1.2 useQrScanner のユニットテスト。
 *
 * <p>カメラ起動 / 動的 import 経路は happy-dom では再現困難なため、副作用関数
 * （{@link pulseVibration} / {@link playBeep}）の非致命動作のみ検証する。</p>
 *
 * <p>start/stop の統合挙動は E2E（JOB-010〜）でカバーする方針。</p>
 */

describe('pulseVibration', () => {
  afterEach(() => {
    // @ts-expect-error delete optional property only available in test setup
    delete (navigator as Navigator & { vibrate?: unknown }).vibrate
  })

  it('navigator.vibrate が存在すれば呼び出す', () => {
    const spy = vi.fn()
    // @ts-expect-error navigator.vibrate is stubbed for this test scenario only
    navigator.vibrate = spy
    pulseVibration(42)
    expect(spy).toHaveBeenCalledWith(42)
  })

  it('navigator.vibrate が無ければ throw しない', () => {
    // 未定義のまま
    expect(() => pulseVibration(50)).not.toThrow()
  })

  it('vibrate が throw しても呼び出し元に伝搬させない', () => {
    // @ts-expect-error navigator.vibrate is stubbed to throw in this scenario
    navigator.vibrate = () => {
      throw new Error('blocked')
    }
    expect(() => pulseVibration(50)).not.toThrow()
  })
})

describe('playBeep', () => {
  const originalAudioCtor = (window as unknown as { AudioContext?: unknown }).AudioContext

  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    // @ts-expect-error restore original AudioContext after stub manipulation
    window.AudioContext = originalAudioCtor
  })

  it('AudioContext が無ければ noop（throw しない）', () => {
    // @ts-expect-error delete optional AudioContext for negative scenario
    delete window.AudioContext
    // @ts-expect-error delete optional webkit AudioContext for negative scenario
    delete (window as unknown as { webkitAudioContext?: unknown }).webkitAudioContext
    expect(() => playBeep(10)).not.toThrow()
  })

  it('AudioContext があれば oscillator を作って start/stop する', () => {
    const oscStart = vi.fn()
    const oscStop = vi.fn()
    const oscConnect = vi.fn()
    const gainConnect = vi.fn()
    const close = vi.fn().mockResolvedValue(undefined)

    class FakeAudioContext {
      destination = {}
      createOscillator() {
        return {
          type: '',
          frequency: { value: 0 },
          connect: oscConnect,
          start: oscStart,
          stop: oscStop,
        }
      }
      createGain() {
        return { gain: { value: 0 }, connect: gainConnect }
      }
      close() {
        return close()
      }
    }
    // @ts-expect-error assign fake AudioContext class for test scenario only
    window.AudioContext = FakeAudioContext

    playBeep(100)
    expect(oscStart).toHaveBeenCalled()
    expect(oscStop).not.toHaveBeenCalled()
    vi.advanceTimersByTime(101)
    expect(oscStop).toHaveBeenCalled()
  })

  it('AudioContext ctor が throw しても呼び出し元に伝搬させない', () => {
    // autoplay policy 等で AudioContext ctor が throw した場合も落ちないこと。
    // 関数コンストラクタ形式で throw を表現（クラス構文だと no-extraneous-class に抵触するため）
    const throwingCtor = function ThrowingAudioContext() {
      throw new Error('blocked')
    }
    // @ts-expect-error stub AudioContext ctor to throw for negative scenario
    window.AudioContext = throwingCtor
    expect(() => playBeep(10)).not.toThrow()
  })
})

/**
 * BarcodeDetector / zxing 経路の分岐ロジックは "window.BarcodeDetector の有無" で決まる。
 * ここでは純粋な判定関数だけを模倣テストする（実体は useQrScanner 内部で定義されているため、
 * 同等ロジックをテスト側で再現して挙動契約を固定する）。
 */
describe('BarcodeDetector 可用性判定（挙動契約）', () => {
  afterEach(() => {
    // @ts-expect-error delete BarcodeDetector stub after the test run
    delete window.BarcodeDetector
  })

  function hasBarcodeDetector(): boolean {
    if (typeof window === 'undefined') return false
    return typeof (window as unknown as { BarcodeDetector?: unknown }).BarcodeDetector === 'function'
  }

  it('window.BarcodeDetector が無ければ false', () => {
    // 未設定
    expect(hasBarcodeDetector()).toBe(false)
  })

  it('window.BarcodeDetector があれば true', () => {
    // @ts-expect-error assign BarcodeDetector stub for availability check
    window.BarcodeDetector = function MockDetector() {}
    expect(hasBarcodeDetector()).toBe(true)
  })
})
