// @vitest-environment happy-dom
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDashboardWidgets } from './useDashboardWidgets'

const external = vi.hoisted(() => ({
  api: vi.fn(),
  settings: vi.fn(),
  auth: vi.fn(),
  toast: vi.fn(),
}))
afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)
afterEach(async () => {
  // 残った外部境界のPromiseも解決し、次のcaseへ待機処理を持ち越さない。
  for (let index = 0; index < saves.length; index += 1) {
    saves[index]!.commit()
    await flushPromises()
  }
})

type SaveBody = { widgets: { widgetKey: string; isVisible: boolean }[] }
let committedVisible: boolean
let saves: { commit: () => void; fail: () => void; body: SaveBody }[]
let mountReads: boolean[]
let owner: Ref<number | null>

async function dashboard(initialVisible = false, scope: 'personal' | 'team' = 'personal') {
  committedVisible = initialVisible
  external.settings.mockReturnValue({
    data: ref([{ widgetKey: 'PERSONAL_DINOSAUR_RANCH', visible: initialVisible, sortOrder: 0 }]),
    status: ref('success'),
  })
  const Ranch = defineComponent({
    setup() {
      // 子の初回GETが読むのは、PUTの応答前にはまだ旧設定である。
      mountReads.push(committedVisible)
      return () => h('div', { 'data-ranch': '' })
    },
  })
  let widgets!: ReturnType<typeof useDashboardWidgets>
  const wrapper = mount(
    defineComponent({
      setup() {
        widgets = useDashboardWidgets(scope)
        return () =>
          widgets.visibleWidgets.value.some((widget) => widget.key === 'dinosaur-ranch')
            ? h(Ranch)
            : null
      },
    }),
  )
  return { widgets, wrapper }
}

beforeEach(() => {
  vi.stubGlobal('ref', ref)
  vi.stubGlobal('computed', computed)
  vi.stubGlobal('watch', watch)
  vi.stubGlobal('useApi', () => external.api)
  vi.stubGlobal('useAsyncData', external.settings)
  vi.stubGlobal('useAuthStore', () => external.auth())
  vi.stubGlobal('useNuxtApp', () => ({
    $i18n: { t: (key: string) => key },
    $toast: { add: external.toast },
  }))
  vi.clearAllMocks()
  saves = []
  mountReads = []
  owner = ref(1)
  external.auth.mockReturnValue({
    get user() {
      return owner.value === null ? null : { id: owner.value }
    },
  })
  external.api.mockImplementation(
    (_path: string, options: { body: SaveBody }) =>
      new Promise<void>((resolve, reject) => {
        saves.push({
          body: options.body,
          commit: () => {
            const ranch = options.body.widgets.find(
              (widget) => widget.widgetKey === 'PERSONAL_DINOSAUR_RANCH',
            )
            if (ranch) committedVisible = ranch.isVisible
            resolve()
          },
          fail: () => reject(new Error('save failed')),
        })
      }),
  )
})

describe('恐竜widgetの保存確定と初回mount', () => {
  it('初回のnullから本人への認証復元後も表示設定を保存できる', async () => {
    owner.value = null
    const { widgets } = await dashboard()
    owner.value = 1
    widgets.toggleWidget('dinosaur-ranch')
    await flushPromises()
    expect(saves).toHaveLength(1)
    saves[0]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([true])
  })

  it('保存中の古い設定GETは最新の表示意図と順序を上書きしない', async () => {
    const { widgets } = await dashboard()
    widgets.toggleWidget('dinosaur-ranch')
    widgets.reorder(0, 1)
    const intendedOrder = [...widgets.orderedKeys.value]
    const settings = external.settings.mock.results[0]!.value as {
      data: Ref<{ widgetKey: string; visible: boolean; sortOrder: number }[]>
    }
    settings.data.value = [{ widgetKey: 'PERSONAL_DINOSAUR_RANCH', visible: false, sortOrder: 0 }]
    await flushPromises()
    expect(widgets.isVisible('dinosaur-ranch')).toBe(true)
    expect(widgets.orderedKeys.value).toEqual(intendedOrder)
    saves[0]!.commit()
    await flushPromises()
    saves[1]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([true])
  })
  it('表示PUTのACKまではmountせず、初回GETで保存後のtrueを読む', async () => {
    const { widgets, wrapper } = await dashboard()

    widgets.toggleWidget('dinosaur-ranch')
    await flushPromises()
    expect(widgets.isVisible('dinosaur-ranch')).toBe(true)
    expect(mountReads).toEqual([])

    saves[0]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([true])
    expect(wrapper.find('[data-ranch]').exists()).toBe(true)
  })

  it('表示保存失敗はmountせず設定を戻して既存のエラー通知を出す', async () => {
    const { widgets } = await dashboard()
    widgets.toggleWidget('dinosaur-ranch')
    await flushPromises()
    saves[0]!.fail()
    await flushPromises()
    expect(mountReads).toEqual([])
    expect(widgets.isVisible('dinosaur-ranch')).toBe(false)
    expect(external.toast).toHaveBeenCalledOnce()
  })

  it('非表示はACK前に即座にunmountし、再表示は古いACKでmountしない', async () => {
    const { widgets, wrapper } = await dashboard(true)
    widgets.toggleWidget('dinosaur-ranch')
    await flushPromises()
    expect(wrapper.find('[data-ranch]').exists()).toBe(false)
    widgets.toggleWidget('dinosaur-ranch')
    await flushPromises()
    saves[0]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([true])
    saves[1]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([true, true])
  })

  it('show hide showを操作順で保存し、最終ACKまでmountしない', async () => {
    const { widgets } = await dashboard()
    widgets.toggleWidget('dinosaur-ranch')
    widgets.toggleWidget('dinosaur-ranch')
    widgets.toggleWidget('dinosaur-ranch')
    await flushPromises()
    expect(saves).toHaveLength(1)
    saves[0]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([])
    saves[1]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([])
    saves[2]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([true])
    expect(committedVisible).toBe(true)
  })

  it.each(['owner', 'unmount'])('%s変更後の遅延ACKは表示を解放しない', async (boundary) => {
    const { widgets, wrapper } = await dashboard()
    widgets.toggleWidget('dinosaur-ranch')
    await flushPromises()
    widgets.toggleWidget('dinosaur-ranch')
    widgets.toggleWidget('dinosaur-ranch')
    if (boundary === 'owner') owner.value = 2
    else wrapper.unmount()
    saves[0]!.commit()
    await flushPromises()
    expect(widgets.visibleWidgets.value.some((widget) => widget.key === 'dinosaur-ranch')).toBe(
      false,
    )
    expect(mountReads).toEqual([])
    expect(saves).toHaveLength(1)
  })

  it('古い保存失敗でも後続の表示意図を巻き戻さず、最新保存の成功後にmountする', async () => {
    const { widgets } = await dashboard()
    widgets.toggleWidget('dinosaur-ranch')
    widgets.toggleWidget('dinosaur-ranch')
    widgets.toggleWidget('dinosaur-ranch')
    await flushPromises()
    saves[0]!.fail()
    await flushPromises()
    expect(widgets.isVisible('dinosaur-ranch')).toBe(true)
    expect(mountReads).toEqual([])
    saves[1]!.commit()
    await flushPromises()
    saves[2]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([true])
  })

  it('表示に続く並び替えも操作時の可視性を保存し、最新ACKでmountする', async () => {
    const { widgets } = await dashboard()
    widgets.toggleWidget('dinosaur-ranch')
    widgets.reorder(0, 1)
    await flushPromises()
    saves[0]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([])
    expect(
      saves[1]!.body.widgets.find((widget) => widget.widgetKey === 'PERSONAL_DINOSAUR_RANCH')!
        .isVisible,
    ).toBe(true)
    saves[1]!.commit()
    await flushPromises()
    expect(mountReads).toEqual([true])
  })

  it('一般widgetの表示は保存ACKを待たず楽観反映する', async () => {
    const { widgets } = await dashboard()
    widgets.toggleWidget('weather')
    await flushPromises()
    saves[0]!.commit()
    await flushPromises()
    widgets.toggleWidget('weather')
    expect(widgets.visibleWidgets.value.some((widget) => widget.key === 'weather')).toBe(true)
  })

  it('teamの保存は既存どおり即送信し、表示も楽観反映する', async () => {
    const { widgets } = await dashboard(false, 'team')
    widgets.toggleWidget('todos')
    widgets.toggleWidget('todos')
    expect(saves).toHaveLength(2)
    expect(widgets.visibleWidgets.value.some((widget) => widget.key === 'todos')).toBe(true)
  })
})
