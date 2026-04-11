<script setup lang="ts">
import type { AnnualViewMonth, EventCategory, CopyPreviewItem } from '~/types/annual-plan'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const annualPlanApi = useAnnualPlanApi()
const notification = useNotification()

const months = ref<AnnualViewMonth[]>([])
const categories = ref<EventCategory[]>([])
const loading = ref(true)
const selectedYear = ref(
  new Date().getMonth() >= 3 ? new Date().getFullYear() : new Date().getFullYear() - 1,
)
const selectedCategoryId = ref<number | undefined>()
const showCopyDialog = ref(false)
const showPreviewDialog = ref(false)
const copyLoading = ref(false)
const previewLoading = ref(false)
const previewItems = ref<CopyPreviewItem[]>([])

const yearOptions = computed(() => [selectedYear.value - 1, selectedYear.value, selectedYear.value + 1])

const categoryOptions = computed(() => [
  { id: undefined as number | undefined, name: t('annual_plan.all_categories') },
  ...categories.value,
])

async function loadData() {
  loading.value = true
  try {
    const res = await annualPlanApi.getAnnualView('team', teamId.value, {
      academicYear: selectedYear.value,
      categoryIds: selectedCategoryId.value !== undefined ? [selectedCategoryId.value] : undefined,
    })
    months.value = res.months
    categories.value = res.categories
  } catch {
    notification.error(t('annual_plan.load_error'))
  } finally {
    loading.value = false
  }
}

async function openCopyDialog() {
  showCopyDialog.value = true
}

async function loadPreview() {
  previewLoading.value = true
  try {
    const preview = await annualPlanApi.getCopyPreview(
      'team',
      teamId.value,
      selectedYear.value - 1,
      selectedYear.value,
    )
    previewItems.value = preview.items
    showCopyDialog.value = false
    showPreviewDialog.value = true
  } catch {
    notification.error(t('annual_plan.load_error'))
  } finally {
    previewLoading.value = false
  }
}

async function handleCopy() {
  copyLoading.value = true
  try {
    await annualPlanApi.executeCopy('team', teamId.value, {
      sourceYear: selectedYear.value - 1,
      targetYear: selectedYear.value,
      dateShiftMode: 'SAME_WEEKDAY',
      items: previewItems.value
        .filter((item) => item.conflict === null)
        .map((item) => ({
          sourceScheduleId: item.sourceScheduleId,
          targetStartAt: item.suggestedStartAt,
          targetEndAt: item.suggestedEndAt,
          include: true,
        })),
    })
    notification.success(t('annual_plan.copy_success'))
    showPreviewDialog.value = false
    await loadData()
  } catch {
    notification.error(t('annual_plan.copy_error'))
  } finally {
    copyLoading.value = false
  }
}

function formatDate(isoString: string): string {
  return isoString.substring(0, 10)
}

watch([selectedYear, selectedCategoryId], () => loadData())
onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ $t('annual_plan.title') }}</h1>
      <div class="flex items-center gap-3">
        <Select
          v-model="selectedYear"
          :options="yearOptions"
          class="w-28"
        />
        <Select
          v-model="selectedCategoryId"
          :options="categoryOptions"
          option-label="name"
          option-value="id"
          :placeholder="$t('annual_plan.category_placeholder')"
          class="w-40"
        />
        <Button
          :label="$t('annual_plan.copy_prev_year')"
          icon="pi pi-copy"
          severity="secondary"
          size="small"
          @click="openCopyDialog"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="space-y-6">
      <div
        v-for="month in months"
        :key="month.month"
        class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <h2 class="mb-3 text-lg font-semibold">{{ month.month }}</h2>
        <div v-if="month.events.length === 0" class="text-sm text-surface-400">
          {{ $t('annual_plan.no_events') }}
        </div>
        <div v-else class="space-y-2">
          <div
            v-for="event in month.events"
            :key="event.id"
            class="flex items-center gap-3 rounded-lg p-2 hover:bg-surface-50 dark:hover:bg-surface-700"
          >
            <div
              v-if="event.eventCategory?.color"
              class="h-3 w-3 flex-shrink-0 rounded-full"
              :style="{ backgroundColor: event.eventCategory.color }"
            />
            <div class="flex-1 min-w-0">
              <p class="text-sm font-medium truncate">{{ event.title }}</p>
              <p class="text-xs text-surface-400">
                {{ formatDate(event.startAt) }}{{ event.endAt ? ` - ${formatDate(event.endAt)}` : '' }}
              </p>
            </div>
            <Badge
              v-if="event.eventCategory?.name"
              :value="event.eventCategory.name"
              severity="secondary"
              class="text-xs"
            />
            <Badge
              v-if="event.sourceScheduleId"
              :value="$t('annual_plan.copied_badge')"
              severity="info"
              class="text-xs"
            />
          </div>
        </div>
      </div>
      <div v-if="months.length === 0" class="py-12 text-center text-surface-500">
        <i class="pi pi-calendar mb-2 text-4xl" />
        <p>{{ $t('annual_plan.no_data') }}</p>
      </div>
    </div>

    <!-- 前年度コピー確認ダイアログ -->
    <Dialog
      v-model:visible="showCopyDialog"
      :header="$t('annual_plan.copy_dialog_title')"
      :modal="true"
      class="w-full max-w-md"
    >
      <p class="mb-4">
        {{ $t('annual_plan.copy_dialog_message', { sourceYear: selectedYear - 1, targetYear: selectedYear }) }}
      </p>
      <template #footer>
        <Button
          :label="$t('button.cancel')"
          severity="secondary"
          @click="showCopyDialog = false"
        />
        <Button
          :label="$t('annual_plan.copy_preview')"
          icon="pi pi-eye"
          :loading="previewLoading"
          @click="loadPreview"
        />
      </template>
    </Dialog>

    <!-- プレビューダイアログ -->
    <Dialog
      v-model:visible="showPreviewDialog"
      :header="$t('annual_plan.copy_dialog_title')"
      :modal="true"
      class="w-full max-w-2xl"
    >
      <div class="max-h-96 overflow-y-auto space-y-2">
        <div
          v-for="item in previewItems"
          :key="item.sourceScheduleId"
          class="flex items-start gap-3 rounded-lg border p-3"
          :class="item.conflict ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-950' : 'border-surface-200'"
        >
          <div
            v-if="item.eventCategory?.color"
            class="mt-1 h-3 w-3 flex-shrink-0 rounded-full"
            :style="{ backgroundColor: item.eventCategory.color }"
          />
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium truncate">{{ item.title }}</p>
            <p class="text-xs text-surface-400">
              {{ formatDate(item.suggestedStartAt) }}
              <span v-if="item.dateShiftNote" class="ml-1 text-surface-300">（{{ item.dateShiftNote }}）</span>
            </p>
            <p v-if="item.conflict" class="text-xs text-red-500">
              {{ $t('annual_plan.conflict_with', { title: item.conflict.existingTitle }) }}
            </p>
          </div>
        </div>
      </div>
      <template #footer>
        <Button
          :label="$t('button.cancel')"
          severity="secondary"
          @click="showPreviewDialog = false"
        />
        <Button
          :label="$t('annual_plan.copy_execute')"
          icon="pi pi-copy"
          :loading="copyLoading"
          @click="handleCopy"
        />
      </template>
    </Dialog>
  </div>
</template>
