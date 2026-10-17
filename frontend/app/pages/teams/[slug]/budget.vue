<script setup lang="ts">
import dayjs from 'dayjs'
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import type { FiscalYearResponse, BudgetSummary } from '~/types/budget'
import { toLocalDateString } from '~/utils/localDate'
import {
  budgetFiscalYearSchema,
  type FiscalYearForm,
  type FiscalYearFormInput,
} from '~/utils/budgetFiscalYearForm'

definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
const { t, locale } = useI18n()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()
const { getFiscalYears, getSummary, createFiscalYear } = useBudgetApi()
const { userTimezone } = useDatetime()
const fiscalYears = ref<FiscalYearResponse[]>([])
const selectedFy = ref<FiscalYearResponse | null>(null)
const summary = ref<BudgetSummary | null>(null)
const loading = ref(true)
const summaryLoading = ref(false)
const loadError = shallowRef<unknown>(null)
const summaryError = shallowRef<unknown>(null)
// 年度一覧は管理者限定。BEの祖先スコープ認可を通過した結果を導線の判定にも使う。
const canCreate = ref(false)
const showGuide = ref(false)
const showCreateDialog = ref(false)
const saving = ref(false)
let summaryRequest = 0

const schema = budgetFiscalYearSchema(t)
const { defineField, handleSubmit, errors, resetForm, setErrors, isSubmitting } =
  useForm<FiscalYearFormInput, FiscalYearForm>({
    validationSchema: toTypedSchema(schema),
    initialValues: { name: '', startDate: null, endDate: null },
  })
const [name] = defineField('name')
const [startDate] = defineField('startDate')
const [endDate] = defineField('endDate')
const money = (value: number) =>
  new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency: 'JPY',
    maximumFractionDigits: 0,
  }).format(value)

function openCreateDialog() {
  if (!canCreate.value || saving.value) return
  const year = dayjs().tz(userTimezone.value).year()
  resetForm({
    values: {
      name: t('budget_accounting.default_name', { year }),
      startDate: new Date(year, 3, 1),
      endDate: new Date(year + 1, 2, 31),
    },
  })
  showCreateDialog.value = true
}
const submit = handleSubmit(async (values) => {
  if (!canCreate.value || saving.value) return
  saving.value = true
  try {
    const response = await createFiscalYear('team', teamSlug, {
      name: values.name,
      startDate: toLocalDateString(values.startDate!),
      endDate: toLocalDateString(values.endDate!),
    })
    fiscalYears.value = [...fiscalYears.value, response.data]
    showCreateDialog.value = false
    notification.success(t('budget_accounting.created'))
    await selectFy(response.data)
  } catch (error) {
    setErrors(getFieldErrors(error))
    handleApiError(error, '会計年度の追加')
  } finally {
    saving.value = false
  }
})
async function handleCreate() {
  if (saving.value || isSubmitting.value) return
  await submit()
}
async function load() {
  loading.value = true
  loadError.value = null
  canCreate.value = false
  selectedFy.value = null
  summary.value = null
  summaryError.value = null
  summaryRequest++
  try {
    const response = await getFiscalYears('team', teamSlug)
    fiscalYears.value = response.data
    canCreate.value = true
    if (response.data[0]) await selectFy(response.data[0])
  } catch (error) {
    loadError.value = error
    handleApiError(error, '会計年度の取得')
  } finally {
    loading.value = false
  }
}
async function selectFy(fy: FiscalYearResponse) {
  const request = ++summaryRequest
  selectedFy.value = fy
  summary.value = null
  summaryError.value = null
  summaryLoading.value = true
  try {
    const response = await getSummary('team', teamSlug, fy.id)
    if (request === summaryRequest) summary.value = response.data
  } catch (error) {
    if (request !== summaryRequest) return
    summaryError.value = error
    handleApiError(error, '予算集計の取得')
  } finally {
    if (request === summaryRequest) summaryLoading.value = false
  }
}
function changeFy(id: number) {
  const fy = fiscalYears.value.find((item) => item.id === id)
  if (fy) void selectFy(fy)
}
onMounted(load)
onBeforeUnmount(() => {
  summaryRequest++
})
</script>

<template>
  <div>
    <PageHeader :title="t('budget_accounting.title')" help @help="showGuide = true">
      <template #actions>
        <Button
          v-if="canCreate"
          :label="t('budget_accounting.add_year')"
          icon="pi pi-plus"
          size="small"
          class="min-h-11"
          data-testid="add-fiscal-year"
          @click="openCreateDialog"
        />
      </template>
    </PageHeader>
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="loadError" :error="loadError" @retry="load" />
    <template v-else>
      <div v-if="fiscalYears.length" class="mb-4 overflow-x-auto">
        <SelectButton
          :model-value="selectedFy?.id"
          :options="fiscalYears"
          class="[&_.p-togglebutton]:min-h-11"
          option-label="name"
          option-value="id"
          :allow-empty="false"
          :aria-label="t('budget_accounting.fiscal_year')"
          @update:model-value="changeFy"
        />
      </div>
      <DashboardEmptyState
        v-else
        icon="pi pi-wallet"
        :message="t('budget_accounting.empty_years')"
        :sub-message="t('budget_accounting.empty_years_hint')"
      />
      <PageLoading v-if="summaryLoading" />
      <DashboardErrorState
        v-else-if="summaryError"
        :error="summaryError"
        @retry="selectedFy && selectFy(selectedFy)"
      />
      <template v-else-if="summary">
        <div class="grid gap-4 sm:grid-cols-3">
          <SectionCard
            v-for="metric in ['totalIncome', 'totalExpense', 'balance'] as const"
            :key="metric"
          >
            <p class="text-xs text-surface-500">{{ t(`budget_accounting.${metric}`) }}</p>
            <p
              class="text-2xl font-bold"
              :class="
                metric === 'totalExpense' || summary[metric] < 0 ? 'text-red-500' : 'text-primary'
              "
            >
              {{ money(summary[metric]) }}
            </p>
          </SectionCard>
        </div>
        <SectionCard class="mt-4">
          <h2 class="mb-3 font-semibold">{{ t('budget_accounting.categories') }}</h2>
          <DashboardEmptyState
            v-if="!summary.categories.length"
            icon="pi pi-list"
            :message="t('budget_accounting.empty_categories')"
          />
          <div v-else class="space-y-3">
            <div
              v-for="cat in summary.categories"
              :key="cat.categoryId"
              class="flex flex-wrap items-center justify-between gap-2 border-b border-surface-100 py-2 dark:border-surface-700"
            >
              <span>{{ cat.categoryName }}</span>
              <div class="flex flex-wrap gap-3 text-sm">
                <span>{{
                  t('budget_accounting.budget_amount', { amount: money(cat.budgetAmount) })
                }}</span>
                <span :class="cat.categoryType === 'INCOME' ? 'text-green-600' : 'text-red-500'">{{
                  t('budget_accounting.actual_amount', { amount: money(cat.actualAmount) })
                }}</span>
                <span
                  :class="cat.executionRate > 100 ? 'font-bold text-red-500' : 'text-surface-500'"
                  >{{ t('budget_accounting.execution_rate', { rate: cat.executionRate }) }}</span
                >
              </div>
            </div>
          </div>
        </SectionCard>
      </template>
    </template>
    <Dialog
      v-model:visible="showCreateDialog"
      modal
      :header="t('budget_accounting.add_year')"
      class="w-full max-w-md"
      :closable="!saving"
      :close-on-escape="!saving"
    >
      <form id="fiscal-year-form" class="flex flex-col gap-4 py-2" @submit.prevent="handleCreate">
        <div class="flex flex-col gap-1">
          <label for="fiscal-year-name">{{ t('budget_accounting.name') }}</label>
          <InputText
            id="fiscal-year-name"
            v-model="name"
            class="text-base"
            :placeholder="t('budget_accounting.name_placeholder')"
            :invalid="!!errors.name"
            :disabled="saving"
            aria-required="true"
            aria-describedby="fiscal-year-name-error"
          />
          <small id="fiscal-year-name-error" class="text-red-500">{{ errors.name }}</small>
        </div>
        <div class="flex flex-col gap-1">
          <label for="fiscal-year-start">{{ t('budget_accounting.start_date') }}</label>
          <DatePicker
            v-model="startDate"
            input-class="text-base"
            input-id="fiscal-year-start"
            date-format="yy/mm/dd"
            show-icon
            :invalid="!!errors.startDate"
            :disabled="saving"
            aria-required="true"
            aria-describedby="fiscal-year-start-error"
          />
          <small id="fiscal-year-start-error" class="text-red-500">{{ errors.startDate }}</small>
        </div>
        <div class="flex flex-col gap-1">
          <label for="fiscal-year-end">{{ t('budget_accounting.end_date') }}</label>
          <DatePicker
            v-model="endDate"
            input-class="text-base"
            input-id="fiscal-year-end"
            date-format="yy/mm/dd"
            show-icon
            :invalid="!!errors.endDate"
            :disabled="saving"
            aria-required="true"
            aria-describedby="fiscal-year-end-error"
          />
          <small id="fiscal-year-end-error" class="text-red-500">{{ errors.endDate }}</small>
        </div>
      </form>
      <template #footer>
        <Button
          :label="t('button.cancel')"
          text
          :disabled="saving"
          @click="showCreateDialog = false"
        />
        <Button
          type="submit"
          form="fiscal-year-form"
          :label="t('budget_accounting.add')"
          icon="pi pi-check"
          :loading="saving"
          :disabled="saving || isSubmitting"
        />
      </template>
    </Dialog>
    <BudgetGuideModal v-model:visible="showGuide" />
  </div>
</template>
