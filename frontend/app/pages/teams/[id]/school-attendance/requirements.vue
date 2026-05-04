<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import type { AtRiskStudentResponse, EvaluationStatus, ResolveEvaluationRequest } from '~/types/school'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const { t } = useI18n()

const {
  atRiskStudents,
  loading,
  resolving,
  statusFilter,
  loadAtRiskStudents,
  resolveViolation,
} = useAttendanceEvaluation()

const evaluationApi = useAttendanceEvaluationApi()

const showResolveModal = ref(false)
const selectedEvaluationId = ref<number | undefined>(undefined)
const resolvingStudent = ref(false)

// フィルター選択値（null = 全件）
const selectedStatus = ref<EvaluationStatus | null>(null)

const filterOptions = computed(() => [
  { label: t('school.requirements.filterAll'), value: null },
  { label: 'VIOLATION', value: 'VIOLATION' as EvaluationStatus },
  { label: 'RISK', value: 'RISK' as EvaluationStatus },
  { label: 'WARNING', value: 'WARNING' as EvaluationStatus },
])

watch(selectedStatus, (val) => {
  statusFilter.value = val ? [val] : []
  void loadAtRiskStudents(teamId.value)
})

async function onResolve(student: AtRiskStudentResponse): Promise<void> {
  resolvingStudent.value = true
  try {
    const evaluations = await evaluationApi.getStudentEvaluations(student.studentUserId)
    const target = evaluations.find(
      e => e.status === 'VIOLATION' && !e.resolvedAt,
    )
    if (target) {
      selectedEvaluationId.value = target.id
      showResolveModal.value = true
    }
  } finally {
    resolvingStudent.value = false
  }
}

async function onResolveSubmit(evaluationId: number, req: ResolveEvaluationRequest): Promise<void> {
  await resolveViolation(evaluationId, req.resolutionNote, teamId.value)
  showResolveModal.value = false
  selectedEvaluationId.value = undefined
}

onMounted(async () => {
  await loadAtRiskStudents(teamId.value)
})
</script>

<template>
  <div class="flex flex-col min-h-screen" data-testid="requirements-page">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamId}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.requirements.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-4xl mx-auto w-full">
      <!-- フィルター行 -->
      <div class="mb-4" data-testid="requirements-filter">
        <SelectButton
          v-model="selectedStatus"
          :options="filterOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
        />
      </div>

      <!-- ローディング中 -->
      <PageLoading v-if="loading || resolvingStudent" />

      <!-- リスト -->
      <template v-else>
        <AtRiskStudentList
          :students="atRiskStudents"
          :loading="resolving"
          data-testid="at-risk-list"
          @resolve="onResolve"
        />
      </template>

      <!-- 解消モーダル -->
      <RequirementResolutionModal
        v-model:visible="showResolveModal"
        :evaluation-id="selectedEvaluationId"
        @resolve="onResolveSubmit"
      />
    </main>
  </div>
</template>
