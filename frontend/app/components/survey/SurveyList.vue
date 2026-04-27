<script setup lang="ts">
import type { SurveyResponse } from '~/types/survey'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const emit = defineEmits<{
  select: [survey: SurveyResponse]
  create: []
}>()

const { t } = useI18n()
const { getSurveys } = useSurveyApi()
// useNotification は { error, success, info, warn } を返す。
// 旧実装は { showError } と destructure しており undefined になっていたため修正。
const { error: showError } = useNotification()
const { relativeTime } = useRelativeTime()

const surveys = ref<SurveyResponse[]>([])
const loading = ref(false)
const statusFilter = ref<string | undefined>(undefined)

async function loadSurveys() {
  loading.value = true
  try {
    const res = await getSurveys(props.scopeType, props.scopeId, statusFilter.value)
    surveys.value = res.data
  } catch {
    showError(t('surveys.list.loadFailed'))
  } finally {
    loading.value = false
  }
}

function getStatusClass(status: string): string {
  switch (status) {
    case 'DRAFT': return 'bg-surface-100 text-surface-600'
    case 'PUBLISHED': return 'bg-green-100 text-green-700'
    case 'CLOSED': return 'bg-red-100 text-red-600'
    default: return 'bg-surface-100'
  }
}

const statusFilterOptions = computed(() => [
  { label: t('surveys.list.filterAll'), value: undefined },
  { label: t('surveys.statusLabel.PUBLISHED'), value: 'PUBLISHED' },
  { label: t('surveys.statusLabel.CLOSED'), value: 'CLOSED' },
])

watch(statusFilter, () => loadSurveys())
onMounted(() => loadSurveys())

defineExpose({ refresh: loadSurveys })
</script>

<template>
  <div data-testid="survey-list-container">
    <div class="mb-4 flex items-center justify-between">
      <Select
        v-model="statusFilter"
        :options="statusFilterOptions"
        option-label="label"
        option-value="value"
        :placeholder="t('surveys.list.filterPlaceholder')"
        class="w-36"
        data-testid="survey-status-filter"
      />
      <Button :label="t('surveys.list.createButton')" icon="pi pi-plus" data-testid="survey-create-button" @click="emit('create')" />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else class="flex flex-col gap-2">
      <button
        v-for="survey in surveys"
        :key="survey.id"
        class="flex items-center gap-4 rounded-xl border border-surface-300 bg-surface-0 p-4 text-left transition-shadow hover:shadow-sm"
        :data-testid="`survey-item-${survey.id}`"
        @click="emit('select', survey)"
      >
        <div class="min-w-0 flex-1">
          <div class="mb-1 flex items-center gap-2">
            <span :class="getStatusClass(survey.status)" class="rounded px-2 py-0.5 text-xs font-medium" :data-testid="`survey-item-status-${survey.id}`">
              {{ t(`surveys.statusLabel.${survey.status}`) }}
            </span>
            <span v-if="survey.isAnonymous" class="text-xs text-surface-400"><i class="pi pi-eye-slash" /> {{ t('surveys.list.anonymous') }}</span>
          </div>
          <h3 class="text-sm font-semibold">{{ survey.title }}</h3>
          <div class="mt-1 flex items-center gap-3 text-xs text-surface-400">
            <span>{{ survey.createdBy?.displayName }}</span>
            <span>{{ relativeTime(survey.createdAt) }}</span>
            <span v-if="survey.deadline"><i class="pi pi-clock" /> {{ survey.deadline }}</span>
          </div>
        </div>
        <div class="text-right">
          <div class="text-sm font-medium">{{ survey.responseCount }}{{ survey.targetCount ? `/${survey.targetCount}` : '' }}</div>
          <div class="text-xs text-surface-400">{{ t('surveys.list.responseCountUnit') }}</div>
          <Badge v-if="survey.hasResponded" :value="t('surveys.list.answeredBadge')" severity="success" class="mt-1" />
        </div>
      </button>

      <div v-if="surveys.length === 0" class="py-12 text-center">
        <i class="pi pi-chart-bar mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">{{ t('surveys.list.empty') }}</p>
      </div>
    </div>
  </div>
</template>
