import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import WidgetReturnStayPlan from '~/components/dashboard/WidgetReturnStayPlan.vue'
import type { ReturnStayPlan } from '~/composables/returnStayPlan/useReturnStayPlanApi'

const list = vi.fn()
const create = vi.fn()
const update = vi.fn()
const remove = vi.fn()
const teamFetch = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useReturnStayPlanApi', () => () => ({ list, create, update, remove }))
mockNuxtImport('useApi', () => () => teamFetch)
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess, error: notifyError }))

const stubs = {
  DashboardWidgetCard: { template: '<section><slot name="actions" /><slot /></section>' },
  Button: {
    props: ['label', 'ariaLabel', 'type', 'disabled'],
    template: '<button :type="type || \'button\'" :aria-label="ariaLabel" :disabled="disabled"><slot />{{ label }}</button>',
  },
  Dialog: {
    props: ['visible'],
    template: '<div v-if="visible"><slot /></div>',
  },
  Message: { template: '<div><slot /></div>' },
}

function plan(overrides: Partial<ReturnStayPlan> = {}): ReturnStayPlan {
  return {
    id: 'plan-1',
    planType: 'HOMECOMING',
    isPublished: true,
    location: { countryCode: 'JP', prefectureCode: '13', regionName: null },
    timezone: 'Asia/Tokyo',
    startDate: '2026-12-20',
    endDate: '2027-01-05',
    teamIds: [7, 13],
    version: 3,
    ...overrides,
  }
}

async function mountWidget(initialPlans: ReturnStayPlan[] = []) {
  list.mockResolvedValue({ data: initialPlans, meta: { total: initialPlans.length } })
  teamFetch.mockResolvedValue({ data: [{ id: 7, name: 'Class 7' }, { id: 13, name: 'Club 13' }] })
  const wrapper = await mountSuspended(WidgetReturnStayPlan, {
    attachTo: document.body,
    global: { stubs },
  })
  await flushPromises()
  return wrapper
}

function field(selector: string): HTMLInputElement | HTMLSelectElement {
  const element = document.querySelector(selector)
  if (!element) throw new Error(`field not found: ${selector}`)
  return element as HTMLInputElement | HTMLSelectElement
}

function setValue(selector: string, value: string) {
  const element = field(selector)
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
  element.dispatchEvent(new Event('change', { bubbles: true }))
}

async function openNew() {
  const add = document.querySelector<HTMLButtonElement>('[aria-label="Add a plan"]')
  if (!add) throw new Error('add button not found')
  add.click()
  await flushPromises()
}

async function submit() {
  const form = document.querySelector('form')
  if (!form) throw new Error('form not found')
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
}

beforeEach(() => {
  list.mockReset()
  create.mockReset()
  update.mockReset()
  remove.mockReset()
  teamFetch.mockReset()
  notifySuccess.mockReset()
  notifyError.mockReset()
  create.mockResolvedValue({ data: plan({ id: 'created', teamIds: [] }) })
  update.mockResolvedValue({ data: plan() })
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('WidgetReturnStayPlan.vue', () => {
  it('新規OFFは空のteamIdsで作成できる', async () => {
    await mountWidget()
    await openNew()
    setValue('#return-stay-prefecture', '01')
    setValue('#return-stay-start', '2026-12-20')
    setValue('#return-stay-end', '2027-01-05')
    document.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await submit()

    expect(create).toHaveBeenCalledWith(expect.objectContaining({ isPublished: false, teamIds: [] }))
  })

  it('既存予定をONからOFFにしても元のteamIdsを保持する', async () => {
    const wrapper = await mountWidget([plan()])
    const edit = wrapper.find('[aria-label="Edit"]')
    await edit.trigger('click')
    document.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await submit()

    expect(update).toHaveBeenCalledWith('plan-1', 3, expect.objectContaining({ isPublished: false, teamIds: [7, 13] }))
  })

  it.each(['01', '13', '47'])('都道府県コード %s を送信する', async (code) => {
    await mountWidget()
    await openNew()
    setValue('#return-stay-prefecture', code)
    setValue('#return-stay-start', '2026-12-20')
    setValue('#return-stay-end', '2027-01-05')
    document.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await submit()

    expect(create).toHaveBeenCalledWith(expect.objectContaining({ location: expect.objectContaining({ prefectureCode: code }) }))
  })

  it('ACTIVE予定では種別と開始日を変更できない', async () => {
    const wrapper = await mountWidget([plan({ startDate: '2026-08-01', endDate: '2026-09-01' })])
    await wrapper.find('[aria-label="Edit"]').trigger('click')

    expect(field('#return-stay-type')).toHaveProperty('disabled', true)
    expect(field('#return-stay-start')).toHaveProperty('disabled', true)
    expect(field('#return-stay-end')).toHaveProperty('disabled', false)
    await (wrapper.vm as unknown as { focusEditDialog: () => Promise<void> }).focusEditDialog()
    expect(document.activeElement).toBe(field('#return-stay-prefecture'))
  })

  it('非ACTIVE予定では初期focusを種別selectに置く', async () => {
    const wrapper = await mountWidget([plan()])
    await wrapper.find('[aria-label="Edit"]').trigger('click')
    await (wrapper.vm as unknown as { focusEditDialog: () => Promise<void> }).focusEditDialog()
    expect(document.activeElement).toBe(field('#return-stay-type'))
  })

  it('409競合は再読込を促す文言を表示する', async () => {
    create.mockRejectedValue({ statusCode: 409 })
    await mountWidget()
    await openNew()
    setValue('#return-stay-prefecture', '13')
    setValue('#return-stay-start', '2026-12-20')
    setValue('#return-stay-end', '2027-01-05')
    document.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await submit()

    expect(document.body.textContent).toContain('returnStayPlan.form.conflict')
  })

  it('初回ロード失敗から再試行できる', async () => {
    list.mockReset()
    list.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({ data: [], meta: { total: 0 } })
    teamFetch.mockResolvedValue({ data: [] })
    await mountSuspended(WidgetReturnStayPlan, {
      attachTo: document.body,
      global: { stubs },
    })
    await flushPromises()
    expect(document.body.textContent).toContain('Could not load plans')

    const retryButton = Array.from(document.querySelectorAll('button')).find((button) => button.textContent?.includes('common.retry'))
    expect(retryButton).toBeTruthy()
    retryButton!.click()
    await flushPromises()
    expect(list).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).not.toContain('Could not load plans')
  })
})
