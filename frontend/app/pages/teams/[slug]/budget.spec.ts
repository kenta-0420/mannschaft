// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
dayjs.extend(utc)
dayjs.extend(timezone)
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import BudgetPage from './budget.vue'
import BudgetGuideModal from '~/components/budget/BudgetGuideModal.vue'
import BudgetGuideContent from '~/components/budget/BudgetGuideContent.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  summary: vi.fn(),
  create: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a' } }))
mockNuxtImport('useBudgetApi', () => () => ({
  getFiscalYears: mocks.list,
  getSummary: mocks.summary,
  createFiscalYear: mocks.create,
}))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('useNotification', () => () => ({ success: mocks.success }))
mockNuxtImport('useErrorHandler', () => () => ({
  handleApiError: mocks.error,
  getFieldErrors: () => ({}),
}))
mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
  tm: () => ({ step1: '' }),
  locale: ref('ja'),
}))

const fy = (id: number) => ({
  id,
  name: `年度${id}`,
  startDate: '2026-04-01',
  endDate: '2027-03-31',
})
const summary = (id: number) => ({
  fiscalYearId: id,
  totalIncome: 0,
  totalExpense: 0,
  balance: 0,
  categories: [],
})
const stubs = {
  PageHeader: {
    props: ['title', 'help'],
    emits: ['help'],
    template:
      '<header>{{title}}<button data-testid="help" @click="$emit(\'help\')">help</button><slot name="actions" /></header>',
  },
  Button: {
    props: ['label', 'disabled'],
    template: '<button :disabled="disabled">{{ label }}</button>',
  },
  Dialog: {
    props: ['visible', 'header'],
    template:
      '<section v-if="visible" role="dialog"><h2>{{header}}</h2><slot /><slot name="footer" /></section>',
  },
  InputText: {
    props: ['modelValue', 'id', 'disabled'],
    emits: ['update:modelValue'],
    template:
      '<input :id="id" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  DatePicker: {
    props: ['modelValue', 'inputId'],
    emits: ['update:modelValue'],
    template: '<input :id="inputId" />',
  },
  SelectButton: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template:
      '<nav><button v-for="fy in options" :key="fy.id" :data-selected="modelValue === fy.id" @click="$emit(\'update:modelValue\', fy.id)">{{fy.name}}</button></nav>',
  },
  SectionCard: { template: '<section><slot /></section>' },
  PageLoading: { template: '<p data-testid="loading" />' },
  DashboardEmptyState: { props: ['message'], template: '<p data-testid="empty">{{message}}</p>' },
  DashboardErrorState: {
    props: ['error'],
    emits: ['retry'],
    template: '<button data-testid="retry" @click="$emit(\'retry\')">error</button>',
  },
}
async function render() {
  const wrapper = mount(BudgetPage, {
    global: {
      stubs,
      mocks: { $t: (key: string) => key },
      components: { BudgetGuideModal, BudgetGuideContent },
    },
  })
  await flushPromises()
  return wrapper
}
async function openForm(wrapper: Awaited<ReturnType<typeof render>>) {
  await wrapper.get('[data-testid="add-fiscal-year"]').trigger('click')
  await wrapper.get('#fiscal-year-name').setValue('  新年度  ')
  const dates = wrapper.findAllComponents(stubs.DatePicker)
  dates[0]!.vm.$emit('update:modelValue', new Date(2026, 3, 1))
  dates[1]!.vm.$emit('update:modelValue', new Date(2027, 2, 31))
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.list.mockResolvedValue({ data: [fy(1)] })
  mocks.summary.mockImplementation((_scope, _slug, id) => Promise.resolve({ data: summary(id) }))
  mocks.create.mockResolvedValue({ data: fy(2) })
})
describe('チーム予算画面', () => {
  it('年度追加の3項目を送り、旧年度があっても新年度を選択し0集計を表示する', async () => {
    const wrapper = await render()
    await openForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(mocks.create).toHaveBeenCalledOnce())
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledWith('team', 'team-a', {
      name: '新年度',
      startDate: '2026-04-01',
      endDate: '2027-03-31',
    })
    expect(wrapper.get('[data-selected="true"]').text()).toBe('年度2')
    expect(wrapper.text()).toContain('￥0')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(mocks.list).toHaveBeenCalledOnce()
  })
  it('BEのcategoriesとbudgetAmount/actualAmount/executionRateで費目を表示する', async () => {
    mocks.summary.mockResolvedValue({
      data: {
        ...summary(1),
        categories: [
          {
            categoryId: 3,
            categoryName: '会場費',
            categoryType: 'EXPENSE',
            budgetAmount: 1000,
            actualAmount: 1200,
            remainingAmount: -200,
            executionRate: 120,
            warningLevel: 'CRITICAL',
          },
        ],
      },
    })
    const wrapper = await render()
    expect(wrapper.text()).toContain('会場費')
    expect(wrapper.find('[data-testid="empty"]').exists()).toBe(false)
  })
  it.each([403, 404])(
    '一般メンバー・他チーム等で一覧GETが%sなら年度追加を出さずエラーを示す',
    async (statusCode) => {
      mocks.list.mockRejectedValue({ statusCode })
      const wrapper = await render()
      expect(wrapper.find('[data-testid="add-fiscal-year"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="retry"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="empty"]').exists()).toBe(false)
      expect(mocks.summary).not.toHaveBeenCalled()
    },
  )
  it('一覧GET失敗の再試行は初回と同じく一覧から集計まで取得する', async () => {
    mocks.list.mockRejectedValueOnce(new Error('offline'))
    const wrapper = await render()
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(mocks.summary).toHaveBeenCalledExactlyOnceWith('team', 'team-a', 1)
    expect(wrapper.find('[data-testid="retry"]').exists()).toBe(false)
  })
  it('集計失敗は前の年度の金額を消して同じ年度を再試行する', async () => {
    mocks.list.mockResolvedValue({ data: [fy(1), fy(2)] })
    mocks.summary
      .mockResolvedValueOnce({ data: { ...summary(1), totalIncome: 500 } })
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ data: summary(2) })
    const wrapper = await render()
    await wrapper.get('nav button:last-child').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('￥500')
    expect(wrapper.find('[data-testid="empty"]').exists()).toBe(false)
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()
    expect(mocks.summary).toHaveBeenLastCalledWith('team', 'team-a', 2)
  })
  it('フォームの空白名を拒否してPOSTしない', async () => {
    const wrapper = await render()
    await openForm(wrapper)
    await wrapper.get('#fiscal-year-name').setValue('   ')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() =>
      expect(wrapper.text()).toContain('budget_accounting.validation.name_required'),
    )
    expect(mocks.create).not.toHaveBeenCalled()
  })
  it('POST失敗では入力とダイアログを保持する', async () => {
    mocks.create.mockRejectedValueOnce(new Error('offline'))
    const wrapper = await render()
    await openForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(mocks.error).toHaveBeenCalledOnce())
    expect(wrapper.get<HTMLInputElement>('#fiscal-year-name').element.value).toBe('  新年度  ')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(mocks.success).not.toHaveBeenCalled()
  })
  it('送信を連打してもPOSTは1回だけにする', async () => {
    let resolve!: (value: { data: ReturnType<typeof fy> }) => void
    mocks.create.mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const wrapper = await render()
    await openForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(mocks.create).toHaveBeenCalledOnce())
    resolve({ data: fy(2) })
    await flushPromises()
    expect(mocks.create).toHaveBeenCalledOnce()
  })
  it('使い方をヘッダーから開き閉じる', async () => {
    const wrapper = await render()
    await wrapper.get('[data-testid="help"]').trigger('click')
    expect(wrapper.text()).toContain('budget_guide.title')
    expect(wrapper.findAll('ol')).toHaveLength(5)
    const close = wrapper.findAll('button').find((button) => button.text() === 'button.close')!
    await close.trigger('click')
    expect(wrapper.find('[data-testid="budget-guide-modal"]').exists()).toBe(false)
  })
})
