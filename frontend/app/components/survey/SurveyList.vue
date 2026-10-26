<script setup lang="ts">
import type { SurveyResponse } from '~/types/survey'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
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
    case 'DRAFT': return 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-200'
    case 'PUBLISHED': return 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-200'
    case 'CLOSED': return 'bg-red-100 text-red-600 dark:bg-red-900/40 dark:text-red-200'
    default: return 'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-200'
  }
}

// DRAFT フィルタを含めた3選択肢（+ 全件）
const statusFilterOptions = computed(() => [
  { label: t('surveys.list.filterAll'), value: undefined },
  { label: t('surveys.list.filterDraft'), value: 'DRAFT' },
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
      <LoadingBounce />
    </div>

    <div v-else class="flex flex-col gap-2">
      <div
        v-for="survey in surveys"
        :key="survey.id"
        class="flex items-center gap-4 rounded-xl border border-surface-300 bg-surface-0 p-4 transition-shadow hover:shadow-sm dark:border-surface-700 dark:bg-surface-800"
        :class="{ 'border-amber-200 bg-amber-50/40 dark:border-amber-700/40 dark:bg-amber-900/10': survey.status === 'DRAFT' }"
        :data-testid="`survey-item-${survey.id}`"
      >
        <!-- クリッカブル本体（詳細画面へ遷移） -->
        <button
          class="min-w-0 flex-1 text-left"
          @click="emit('select', survey)"
        >
          <div class="mb-1 flex items-center gap-2">
            <span :class="getStatusClass(survey.status)" class="rounded px-2 py-0.5 text-xs font-medium" :data-testid="`survey-item-status-${survey.id}`">
              {{ t(`surveys.statusLabel.${survey.status}`) }}
            </span>
            <span v-if="survey.policy?.isAnonymous" class="text-xs text-surface-400"><i class="pi pi-eye-slash" /> {{ t('surveys.list.anonymous') }}</span>
          </div>
          <h3 class="text-sm font-semibold">{{ survey.content?.title }}</h3>
          <div class="mt-1 flex items-center gap-3 text-xs text-surface-400">
            <span>{{ relativeTime(survey.audit?.createdAt) }}</span>
            <span v-if="survey.schedule?.expiresAt"><i class="pi pi-clock" /> {{ survey.schedule.expiresAt }}</span>
          </div>
        </button>

        <!-- 右側: 回答数 or DRAFTの場合は「設問を追加・編集」導線 -->
        <div class="shrink-0 text-right">
          <template v-if="survey.status === 'DRAFT'">
            <!-- DRAFT: 設問追加・公開への導線ボタン -->
            <Button
              :label="t('surveys.list.editDraft')"
              icon="pi pi-pencil"
              size="small"
              severity="warn"
              outlined
              :data-testid="`survey-item-edit-draft-${survey.id}`"
              @click.stop="emit('select', survey)"
            />
          </template>
          <template v-else>
            <div class="text-sm font-medium">{{ survey.stats?.responseCount }}{{ survey.stats?.targetCount ? `/${survey.stats.targetCount}` : '' }}</div>
            <div class="text-xs text-surface-400">{{ t('surveys.list.responseCountUnit') }}</div>
            <Badge v-if="survey.hasResponded" :value="t('surveys.list.answeredBadge')" severity="success" class="mt-1" />
          </template>
        </div>
      </div>

      <div v-if="surveys.length === 0" class="py-12 text-center">
        <i class="pi pi-chart-bar mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">{{ t('surveys.list.empty') }}</p>
      </div>
    </div>
  </div>
</template>
