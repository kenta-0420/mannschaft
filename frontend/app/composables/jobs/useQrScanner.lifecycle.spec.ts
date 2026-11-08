// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, onBeforeUnmount, readonly, ref } from 'vue'
import { useQrScanner } from './useQrScanner'

const zxing = vi.hoisted(() => ({ decodeFromStream: vi.fn() }))
vi.mock('@zxing/browser', () => ({ BrowserMultiFormatReader: class {
  decodeFromStream = zxing.decodeFromStream
} }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
function camera() {
  const stop = vi.fn()
  const stream = new MediaStream()
  // happy-domの正規streamはsrcObjectへ渡せるが、getTracksだけ未実装。
  Object.defineProperty(stream, 'getTracks', { value: () => [{ stop }] })
  return { stream, stop }
}
const mounted: ReturnType<typeof mount>[] = []
const getUserMedia = vi.fn()
const detect = vi.fn()
const detectorCreated = vi.fn()
beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('ref', ref)
  vi.stubGlobal('readonly', readonly)
  vi.stubGlobal('onBeforeUnmount', onBeforeUnmount)
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } })
  vi.stubGlobal('BarcodeDetector', class {
    constructor() { detectorCreated() }
    detect = detect
  })
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
  detect.mockResolvedValue([])
})
afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})
function scanner() {
  let instance!: ReturnType<typeof useQrScanner>
  const wrapper = mount(defineComponent({ setup() {
    instance = useQrScanner()
    return () => h('video')
  } }))
  mounted.push(wrapper)
  return { instance, wrapper, video: wrapper.get('video').element as HTMLVideoElement }
}

describe('カメラ許可・映像開始・検出待ちで停止した場合のresource解放', () => {
  for (const cancelledBy of ['stop', 'unmount'] as const) {
    it(`${cancelledBy}後に許可されたstreamを閉じ、映像やdecodeを開始しない`, async () => {
      const permission = deferred<MediaStream>()
      const late = camera()
      getUserMedia.mockReturnValueOnce(permission.promise)
      const { instance, wrapper, video } = scanner()
      const onScanned = vi.fn()
      const started = instance.start({ videoEl: video, onScanned })
      if (cancelledBy === 'stop') instance.stop()
      else wrapper.unmount()
      permission.resolve(late.stream)
      await started
      expect(late.stop).toHaveBeenCalledTimes(1)
      expect(video.srcObject).not.toBe(late.stream)
      expect(video.play).not.toHaveBeenCalled()
      expect(detectorCreated).not.toHaveBeenCalled()
      expect(instance.state.value).toBe('stopped')
      expect(onScanned).not.toHaveBeenCalled()
    })
  }
  it('映像play待ちの停止後にdecoderを起動しない', async () => {
    const playback = deferred<undefined>()
    const acquired = camera()
    getUserMedia.mockResolvedValueOnce(acquired.stream)
    vi.mocked(HTMLMediaElement.prototype.play).mockReturnValueOnce(playback.promise)
    const { instance, video } = scanner()
    const started = instance.start({ videoEl: video, onScanned: vi.fn() })
    await flushPromises()
    instance.stop()
    playback.resolve(undefined)
    await started
    expect(acquired.stop).toHaveBeenCalledTimes(1)
    expect(detectorCreated).not.toHaveBeenCalled()
    expect(instance.state.value).toBe('stopped')
  })
  it('古い許可応答だけを閉じ、再起動したstreamと検出を維持する', async () => {
    const permission = deferred<MediaStream>()
    const oldCamera = camera()
    const currentCamera = camera()
    getUserMedia.mockReturnValueOnce(permission.promise).mockResolvedValueOnce(currentCamera.stream)
    const { instance, video } = scanner()
    Object.defineProperty(video, 'videoWidth', { value: 640 })
    const oldScanned = vi.fn()
    const currentScanned = vi.fn()
    const oldStart = instance.start({ videoEl: video, onScanned: oldScanned })
    instance.stop()
    await instance.start({ videoEl: video, onScanned: currentScanned })
    permission.resolve(oldCamera.stream)
    await oldStart
    expect(oldCamera.stop).toHaveBeenCalledTimes(1)
    expect(currentCamera.stop).not.toHaveBeenCalled()
    expect(video.srcObject).toBe(currentCamera.stream)
    expect(instance.state.value).toBe('running')
    detect.mockResolvedValueOnce([{ rawValue: 'new-session-qr', format: 'qr_code' }])
    await vi.advanceTimersByTimeAsync(250)
    expect(currentScanned).toHaveBeenCalledWith(expect.objectContaining({ text: 'new-session-qr' }))
    expect(oldScanned).not.toHaveBeenCalled()
    instance.stop()
    expect(currentCamera.stop).toHaveBeenCalledTimes(1)
  })
  it('停止前に開始した検出結果を停止後に通知しない', async () => {
    const detection = deferred<Array<{ rawValue: string; format: string }>>()
    const acquired = camera()
    getUserMedia.mockResolvedValueOnce(acquired.stream)
    detect.mockReturnValueOnce(detection.promise)
    const { instance, video } = scanner()
    Object.defineProperty(video, 'videoWidth', { value: 640 })
    const onScanned = vi.fn()
    await instance.start({ videoEl: video, onScanned })
    vi.advanceTimersByTime(250)
    expect(detect).toHaveBeenCalledTimes(1)
    instance.stop()
    detection.resolve([{ rawValue: 'test-only-qr', format: 'qr_code' }])
    await flushPromises()
    expect(acquired.stop).toHaveBeenCalledTimes(1)
    expect(onScanned).not.toHaveBeenCalled()
  })
  it('ZXing開始待ちの停止後に返るdecoder controlsも閉じる', async () => {
    vi.stubGlobal('BarcodeDetector', undefined)
    const controls = deferred<{ stop: () => void }>()
    const acquired = camera()
    const stopDecoder = vi.fn()
    getUserMedia.mockResolvedValueOnce(acquired.stream)
    zxing.decodeFromStream.mockReturnValueOnce(controls.promise)
    const { instance, video } = scanner()
    const started = instance.start({ videoEl: video, onScanned: vi.fn() })
    await vi.dynamicImportSettled()
    expect(zxing.decodeFromStream).toHaveBeenCalledTimes(1)
    instance.stop()
    controls.resolve({ stop: stopDecoder })
    await started
    expect(acquired.stop).toHaveBeenCalledTimes(1)
    expect(stopDecoder).toHaveBeenCalledTimes(1)
    expect(instance.state.value).toBe('stopped')
  })
})
