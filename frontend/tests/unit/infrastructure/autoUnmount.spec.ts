import { beforeAll, describe, expect, it, onTestFinished, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'

// fake clock の導入前に Nuxt の通常初期化を完了させる。
beforeAll(async () => {
  await mountSuspended(defineComponent({ render: () => h('div') }))
})

describe('共有セットアップのコンポーネント後始末', () => {
  it('DOM 環境の終了後も実 TabList の遅延処理が破棄済み参照を使わない', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    let finishRegistered = false
    try {
      const wrapper = await mountSuspended(defineComponent({
        render: () => h(Tabs, { value: 'first' }, {
          default: () => h(TabList, null, {
            default: () => h(Tab, { value: 'first' }, { default: () => '予定' }),
          }),
        }),
      }))
      const tabList = wrapper.findComponent(TabList)
      const instance = tabList.vm as unknown as {
        updateInkBar: () => void
        $refs: { inkbar: unknown }
        $: { isUnmounted: boolean }
      }
      const updateInkBar = vi.spyOn(instance, 'updateInkBar')
      expect(instance.$.isUnmounted).toBe(false)
      expect(instance.$refs.inkbar).toBeTruthy()

      // Vitest の afterEach 後に Nuxt の DOM 終了境界を再現する。
      // タイマーを消さず実 callback を呼び、標準 auto-unmount の効果を確認する。
      onTestFinished(() => {
        const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'HTMLElement')
        try {
          expect(descriptor).toBeDefined()
          expect(Reflect.deleteProperty(globalThis, 'HTMLElement')).toBe(true)
          vi.advanceTimersByTime(150)
          expect(updateInkBar).toHaveBeenCalledTimes(1)
          expect(instance.$.isUnmounted).toBe(true)
          expect(instance.$refs.inkbar).toBeNull()
        }
        finally {
          if (descriptor) Object.defineProperty(globalThis, 'HTMLElement', descriptor)
          updateInkBar.mockRestore()
          vi.useRealTimers()
        }
      })
      finishRegistered = true
    }
    finally {
      // マウント・事前条件が失敗した場合も fake clock を次の試験へ残さない。
      if (!finishRegistered) vi.useRealTimers()
    }
  })
})
