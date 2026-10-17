// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import sharp from 'sharp'
import { BinaryBitmap, HybridBinarizer, RGBLuminanceSource, QRCodeReader } from '@zxing/library'
import LoyaltyReceptionSettings from './LoyaltyReceptionSettings.vue'
import QrCodeImage from '~/components/qr/QrCodeImage.vue'

const mocks = vi.hoisted(() => ({
  listLocations: vi.fn(),
  getLocationQr: vi.fn(),
  handleApiError: vi.fn(),
  require: vi.fn(),
}))
vi.stubGlobal('useMemberCardApi', () => mocks)
vi.stubGlobal('useErrorHandler', () => mocks)
vi.stubGlobal('useConfirm', () => mocks)
vi.stubGlobal('useI18n', () => ({ t: (key: string) => key }))
vi.stubGlobal('ref', ref)
vi.stubGlobal('shallowRef', shallowRef)
vi.stubGlobal('computed', computed)
vi.stubGlobal('watch', watch)
vi.stubGlobal('onMounted', onMounted)

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.clearAllMocks()
})

describe('受付QRの読み取り', () => {
  it('実受付SFCが生成した139文字の未署名URLを元の画像decoderで読み取れる', async () => {
    // 実tokenを含まない固定合成データ。LCG seed=20261017、最大50候補の11件目で
    // 旧H/余白1の検出失敗とM/余白4の成功を確認。実ブラウザの検証は別途必要。
    const token = 'FAKE.8TU2iZHTvHJnjl507qJQlWzJ7Io9KiCT3gJkFCwkj3O8fa15KLQ0RD8eF5jMJBjl9mEYXZ3uG03'
    window.location.href = 'http://localhost:3000/'
    const expected = `${window.location.origin}/my/team-points/receive#locationToken=${token}`
    mocks.listLocations.mockResolvedValue([{ id: 1, name: '受付', isActive: true }])
    mocks.getLocationQr.mockResolvedValue({ qrToken: token })
    const wrapper = mount(LoyaltyReceptionSettings, {
      props: { teamId: 1, teamName: 'チーム', mode: 'AUTO' },
      global: {
        components: { QrCodeImage },
        stubs: {
          SectionCard: { template: '<section><slot /></section>' },
          PageLoading: true,
          DashboardErrorState: true,
          Select: {
            props: ['modelValue', 'options'],
            emits: ['update:modelValue'],
            template:
              '<select @change="$emit(\'update:modelValue\', 1)"><option value="1">受付</option></select>',
          },
          Button: { props: ['label'], template: '<button>{{ label }}</button>' },
          InputText: true,
        },
      },
    })
    wrappers.push(wrapper)
    await flushPromises()
    await wrapper.get('select').trigger('change')
    const preview = wrapper
      .findAll('button')
      .find((button) => button.text() === 'loyalty.reception.preview')!
    await preview.trigger('click')
    await flushPromises()
    const image = wrapper.getComponent(QrCodeImage)
    const svg = image.get('svg').element.outerHTML
    const { data, info } = await sharp(Buffer.from(svg))
      .removeAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true })
    const pixels = new Uint8ClampedArray(info.width * info.height)
    for (let i = 0; i < pixels.length; i++)
      pixels[i] = Math.round(
        (data[i * info.channels]! + data[i * info.channels + 1]! + data[i * info.channels + 2]!) /
          3,
      )
    let decoded: string | null = null
    try {
      decoded = new QRCodeReader()
        .decode(
          new BinaryBitmap(
            new HybridBinarizer(new RGBLuminanceSource(pixels, info.width, info.height)),
          ),
        )
        .getText()
    } catch {
      // 読み取れない画像はnullのまま、元のURLとの一致assertionで失敗させる。
    }

    expect(expected).toHaveLength(139)
    expect(decoded).toBe(expected)
    expect(image.find('.qr-code-image__badge').exists()).toBe(false)
    expect(image.props('size')).toBe(280)
    expect(image.attributes('aria-label')).toBe('loyalty.reception.printTitle')
  })
})
