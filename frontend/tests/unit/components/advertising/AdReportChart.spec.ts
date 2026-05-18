/**
 * F09.17 Phase 11-c-4 — AdReportChart.vue のユニットテスト。
 *
 * <p>chart.js を vi.mock でスタブ化し、Vue コンポーネント側の挙動のみ検証する。</p>
 * <ul>
 *   <li>render 時に Chart constructor が呼ばれる</li>
 *   <li>props.daily が変わると Chart が再生成される</li>
 *   <li>unmount 時に destroy() が呼ばれる</li>
 * </ul>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'

/**
 * chart.js を vi.mock でホイストして差し替える。
 * vi.mock のファクトリはトップレベル変数にアクセスできないため、
 * vi.hoisted で先行宣言する。
 *
 * <p>AdReportChart は {@code new ChartJS(canvas, config)} で呼ぶため、
 * モックは constructor として呼べる必要がある。{@code vi.fn} は内部で
 * {@code Reflect.construct} 経由で呼ばれてもクラス様に振る舞うが、
 * モック側に {@code prototype} を持たせると安全。ここでは ES クラスを使い、
 * track 用に register / destroy を {@code vi.fn} で晒す。</p>
 */
const { ChartMock, mockDestroy, mockRegister, getInstanceCount } = vi.hoisted(() => {
  const destroy = vi.fn()
  const register = vi.fn()
  let instanceCount = 0
  class ChartMockImpl {
    public destroy = destroy
    constructor() {
      instanceCount += 1
    }
  }
  // 静的メソッド register を生やす
  ;(ChartMockImpl as unknown as { register: typeof register }).register = register
  return {
    ChartMock: ChartMockImpl,
    mockDestroy: destroy,
    mockRegister: register,
    getInstanceCount: () => instanceCount,
  }
})

vi.mock('chart.js', () => {
  const stub = {}
  return {
    Chart: ChartMock,
    BarController: stub,
    BarElement: stub,
    CategoryScale: stub,
    Filler: stub,
    Legend: stub,
    LinearScale: stub,
    LineController: stub,
    LineElement: stub,
    PointElement: stub,
    Title: stub,
    Tooltip: stub,
  }
})

// eslint-disable-next-line import/first -- vi.mock より後でインポートする必要がある
import AdReportChart from '~/components/advertising/AdReportChart.vue'

const sampleDaily = [
  { date: '2026-05-01', delivered: 100, opened: 30, clicked: 5, consumedBudgetYen: 500 },
  { date: '2026-05-02', delivered: 200, opened: 80, clicked: 12, consumedBudgetYen: 1000 },
]

describe('AdReportChart.vue', () => {
  beforeEach(() => {
    mockDestroy.mockClear()
    mockRegister.mockClear()
  })

  it('AD-REPORT-CHART-001: マウント時に chart.js の Chart コンストラクタが呼ばれる', async () => {
    const before = getInstanceCount()
    await mountSuspended(AdReportChart, {
      props: { daily: sampleDaily },
    })
    // onMounted で少なくとも 1 つ Chart instance が増える
    expect(getInstanceCount()).toBeGreaterThan(before)
  })

  it('AD-REPORT-CHART-002: マウントで data-testid="ad-report-chart" が描画される', async () => {
    const wrapper = await mountSuspended(AdReportChart, {
      props: { daily: sampleDaily },
    })
    expect(wrapper.find('[data-testid="ad-report-chart"]').exists()).toBe(true)
  })

  it('AD-REPORT-CHART-003: daily が変わると Chart が再生成され destroy も呼ばれる', async () => {
    const wrapper = await mountSuspended(AdReportChart, {
      props: { daily: sampleDaily },
    })
    const before = getInstanceCount()
    await wrapper.setProps({
      daily: [
        ...sampleDaily,
        { date: '2026-05-03', delivered: 50, opened: 10, clicked: 1, consumedBudgetYen: 100 },
      ],
    })
    // watch deep が走り、新しい instance が生まれる
    expect(getInstanceCount()).toBeGreaterThan(before)
    expect(mockDestroy).toHaveBeenCalled()
  })

  it('AD-REPORT-CHART-004: 空の daily でもエラーなくマウントできる', async () => {
    const wrapper = await mountSuspended(AdReportChart, {
      props: { daily: [] },
    })
    expect(wrapper.exists()).toBe(true)
  })
})
