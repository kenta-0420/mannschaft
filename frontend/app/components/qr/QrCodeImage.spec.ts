import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import QrCodeImage from './QrCodeImage.vue'

describe('QrCodeImage', () => {
  it.each([240, 300, 350, 500])(
    'AC4: %ipxの画面と保存SVGのバッジ左右端が半円になる',
    async (size) => {
      const wrapper = mount(QrCodeImage, {
        props: {
          value: 'https://app.mannschaft.example/invite/capsule-check',
          size,
        },
      })
      await flushPromises()

      const badge = wrapper.get('.qr-code-image__badge').element as HTMLElement
      expect(badge.textContent).toBe('Mannschaft')
      expect(badge.style.borderRadius).toBe('9999px')
      expect(badge.style.paddingTop).toBe(`${Math.round(size * 0.025)}px`)
      const svg = (wrapper.vm as unknown as { getSvg: () => string }).getSvg()
      const document = new DOMParser().parseFromString(svg, 'image/svg+xml')
      const frame = document.querySelector('rect[stroke]')!
      expect(Number(frame.getAttribute('rx'))).toBeCloseTo(
        Number(frame.getAttribute('height')) / 2,
        2,
      )
      expect(frame.getAttribute('ry')).toBe(frame.getAttribute('rx'))
      const units = Number(document.documentElement.getAttribute('viewBox')!.split(' ')[2])
      const centerY = Number(frame.getAttribute('y')) + Number(frame.getAttribute('height')) / 2
      expect(centerY / units).toBeCloseTo(
        0.54 + Number(frame.getAttribute('height')) / units / 2,
        3,
      )
      expect(document.querySelector('text')?.textContent).toBe('Mannschaft')
      wrapper.unmount()
    },
  )

  it('ラベルを空にすると画面にも保存SVGにもバッジを重ねない', async () => {
    const wrapper = mount(QrCodeImage, {
      props: {
        value: 'https://app.mannschaft.example/invite/no-label',
        centerLabel: '',
      },
    })
    await flushPromises()

    expect(wrapper.find('.qr-code-image__badge').exists()).toBe(false)
    const svg = (wrapper.vm as unknown as { getSvg: () => string }).getSvg()
    expect(svg).not.toContain('<text')
    wrapper.unmount()
  })
})
