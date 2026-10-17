// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import LoyaltyMemberQrPanel from './LoyaltyMemberQrPanel.vue'
import QrCodeImage from '~/components/qr/QrCodeImage.vue'
import sharp from 'sharp'
import { BinaryBitmap, HybridBinarizer, RGBLuminanceSource, QRCodeReader } from '@zxing/library'
import type { MemberCardQr } from '~/types/member-card'
import { ref, shallowRef, computed, watch, onMounted, onUnmounted } from 'vue'
import { createI18n } from 'vue-i18n'
const mocks = vi.hoisted(() => ({
  ensurePlatform: vi.fn(),
  getQr: vi.fn(),
  handleApiError: vi.fn(),
}))
vi.mock('~/composables/useMemberCardApi', () => ({ useMemberCardApi: () => mocks }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => mocks }))
vi.stubGlobal('useMemberCardApi', () => mocks)
vi.stubGlobal('useErrorHandler', () => mocks)
vi.stubGlobal('useI18n', () => ({ t: (key: string) => key }))
vi.stubGlobal('ref', ref)
vi.stubGlobal('shallowRef', shallowRef)
vi.stubGlobal('computed', computed)
vi.stubGlobal('watch', watch)
vi.stubGlobal('onMounted', onMounted)
vi.stubGlobal('onUnmounted', onUnmounted)

const qr = (token: string): MemberCardQr => ({
  memberCardId: 1,
  qrToken: token,
  expiresIn: 3,
  cardNumber: 'card',
  displayName: 'Member',
  scopeType: 'PLATFORM',
  scopeName: null,
})
const wrappers: ReturnType<typeof mount>[] = []
function mountPanel(actualQr = false) {
  mocks.ensurePlatform.mockResolvedValue({ memberCardId: 1 })
  const wrapper = mount(LoyaltyMemberQrPanel, {
    global: {
      components: { QrCodeImage },
      plugins: [
        createI18n({ legacy: false, locale: 'ja', missingWarn: false, fallbackWarn: false }),
      ],
      stubs: {
        SectionCard: { template: '<section><slot /></section>' },
        QrCodeImage: actualQr
          ? false
          : { props: ['value'], template: '<div class="qr-render">{{ value }}</div>' },
        PageLoading: true,
        DashboardErrorState: true,
        Button: true,
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
  vi.clearAllMocks()
})
describe('会員QRの実期限と背景復帰（AC01/10/20）', () => {
  it('実Panelが生成した102文字の未署名QRを元の画像decoderで読み取れる', async () => {
    // 実tokenを含まない固定合成データ。有限候補5件目で旧H描画の検出失敗を再現した。
    const unsigned = 'FAKE.vq2ow8F8GLj1Bh-A4TOWhAyQDoD7K__vU4UMEUwHlOJeuJ30rlLB8fD2DyjNtplJdS1IXMxmFM2GpZKBMqDB0gvq2ow8F8GLj'
    mocks.getQr.mockResolvedValue(qr(unsigned))
    const wrapper = mountPanel(true)
    await flushPromises()
    const svg = wrapper.getComponent(QrCodeImage).get('svg').element.outerHTML
    const { data, info } = await sharp(Buffer.from(svg)).removeAlpha().raw()
      .toBuffer({ resolveWithObject: true })
    const pixels = new Uint8ClampedArray(info.width * info.height)
    for (let i = 0; i < pixels.length; i++)
      pixels[i] = Math.round(
        (data[i * info.channels]! + data[i * info.channels + 1]! + data[i * info.channels + 2]!) / 3,
      )
    let decoded: string | null = null
    try {
      decoded = new QRCodeReader().decode(new BinaryBitmap(
        new HybridBinarizer(new RGBLuminanceSource(pixels, info.width, info.height)),
      )).getText()
    } catch {
      // 復号できない画像はnullのまま、生成元の値との一致assertionで失敗させる。
    }
    expect(decoded).toBe(unsigned)
  })
  it('表示中の会員QRを名前付き画像として公開する', async () => {
    mocks.getQr.mockResolvedValue(qr('accessible-token'))
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.find('.qr-render').attributes('role')).toBe('img')
    expect(wrapper.find('.qr-render').attributes('aria-label')).toBe('loyalty.memberQr.title')
  })
  it('2秒遅延した3秒TTL応答を実期限の3秒時点で隠す', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
    let resolve!: (value: MemberCardQr) => void
    mocks.getQr.mockImplementationOnce(
      () =>
        new Promise<MemberCardQr>((done) => {
          resolve = done
        }),
    )
    mocks.getQr.mockImplementation(() => new Promise<MemberCardQr>(() => {}))
    const wrapper = mountPanel()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(2000)
    resolve(qr('old-token'))
    await flushPromises()
    expect(wrapper.find('.qr-render').text()).toBe('old-token')
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.find('.qr-render').exists()).toBe(false)
    wrapper.unmount()
  })
  it('背景復帰中の新しい要求が成功したあと古い要求が戻っても差し替えない', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
    let resolveOld!: (value: MemberCardQr) => void
    mocks.getQr.mockImplementationOnce(
      () =>
        new Promise<MemberCardQr>((done) => {
          resolveOld = done
        }),
    )
    mocks.getQr.mockResolvedValue(qr('fresh-token'))
    const wrapper = mountPanel()
    await flushPromises()
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(wrapper.find('.qr-render').exists()).toBe(true)
    expect(wrapper.find('.qr-render').text()).toBe('fresh-token')
    resolveOld(qr('stale-token'))
    await flushPromises()
    expect(wrapper.find('.qr-render').text()).toBe('fresh-token')
    wrapper.unmount()
  })
})
