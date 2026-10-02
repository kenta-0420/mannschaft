import { describe, expect, it } from 'vitest'
import { createSSRApp, defineComponent, h, nextTick } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { mount } from '@vue/test-utils'
import { useHydrated } from '../../../app/composables/useHydrated'

/**
 * SSR 出力とクライアント初回レンダー出力の一致を検査するための最小コンポーネント。
 * ログイン画面の送信ボタンと同じ形（`:disabled="!hydrated"`）を再現する。
 */
function createProbe(renderedHydratedValues: boolean[]) {
  return defineComponent({
    name: 'HydratedProbe',
    setup() {
      const hydrated = useHydrated()

      return () => {
        renderedHydratedValues.push(hydrated.value)

        return h('button', { type: 'submit', disabled: !hydrated.value }, 'submit')
      }
    },
  })
}

describe('useHydrated', () => {
  it('SSR 出力とクライアント初回レンダー出力が一致する（hydration mismatch を起こさない）', async () => {
    const ssrRenderedValues: boolean[] = []
    const ssrHtml = await renderToString(createSSRApp(createProbe(ssrRenderedValues)))

    const clientRenderedValues: boolean[] = []
    const wrapper = mount(createProbe(clientRenderedValues))

    // SSR 側は常に false（マウント系フックが走らない）
    expect(ssrRenderedValues[0]).toBe(false)
    expect(ssrHtml).toContain('disabled')

    // クライアント初回レンダー（＝ハイドレーション用レンダー）も false でなければならない。
    // ここが true になる実装（onBeforeMount で立てる等）は SSR 出力と食い違い、
    // Vue は属性の不一致を修正しないため disabled が SSR の値のまま凍結する。
    expect(clientRenderedValues[0]).toBe(false)

    wrapper.unmount()
  })

  it('マウント完了後に true となり、送信ボタンの disabled が外れる', async () => {
    const renderedValues: boolean[] = []
    const wrapper = mount(createProbe(renderedValues))

    await nextTick()

    expect(renderedValues.at(-1)).toBe(true)
    expect(wrapper.find('button').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })
})
