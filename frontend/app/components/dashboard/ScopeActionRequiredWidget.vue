<script setup lang="ts">
/**
 * F22.1 統合「要対応」ウィジェット（⑧）。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §5
 * - 回覧板 / アンケート / 出席確認 の 3 区分を 1 枚に集約。
 * - ビューポート進入時に GET /dashboard/{scope}/{id}/action-required を遅延取得（02 §3.3）。
 * - 各ドメインの per-scope 認可は BE 側で必ず通る。FE は集計結果を表示するのみ。
 */
import type { ScopeTabType, ActionRequiredSummary } from '~/types/dashboard-scope'

const props = defineProps<{
  scopeType: ScopeTabType
  scopeId: string
}>()

const { getActionRequired } = useScopeTabApi()

const summary = ref<ActionRequiredSummary | null>(null)
const loading = ref(false)
const errorKey = ref<string | null>(null)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

async function fetchSummary() {
  if (loaded.value || loading.value) return
  loading.value = true
  errorKey.value = null
  try {
    summary.value = await getActionRequired(props.scopeType, props.scopeId)
    loaded.value = true
  } catch (e) {
    // 握り潰さない。i18n キーを保持して UI に表示する。
    console.error('[ScopeActionRequiredWidget] fetch failed', e)
    errorKey.value = 'swipeWidgets.actionRequired.loadError'
  } finally {
    loading.value = false
  }
}

// scopeId が変わったら再取得対象に戻す。
watch(
  () => props.scopeId,
  () => {
    loaded.value = false
    summary.value = null
    if (rootEl.value) observeViewport()
  },
)

function observeViewport() {
  if (!import.meta.client || !rootEl.value) return
  observer?.disconnect()
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        fetchSummary()
        observer?.disconnect()
      }
    }
  })
  observer.observe(rootEl.value)
}

onMounted(() => {
  observeViewport()
})

onBeforeUnmount(() => {
  observer?.disconnect()
})

const isEmpty = computed(
  () => loaded.value && (summary.value?.totalActionCount ?? 0) === 0,
)

const surveyListPath = computed(() =>
  props.scopeType === 'TEAM'
    ? `/teams/${props.scopeId}/surveys`
    : `/organizations/${props.scopeId}/surveys`,
)
</script>

<template>
  <div ref="rootEl">
    <SectionCard :title="$t('swipeWidgets.actionRequired.title')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <Message v-else-if="errorKey" severity="error" :closable="false">
        {{ $t(errorKey) }}
      </Message>

      <DashboardEmptyState
        v-else-if="isEmpty"
        icon="pi pi-check-circle"
        :message="$t('swipeWidgets.actionRequired.empty')"
      />

      <div v-else-if="summary" class="flex flex-col gap-3">
        <!-- 回覧板 -->
        <div>
          <button
            type="button"
            class="flex w-full items-center justify-between text-left text-sm font-medium hover:text-primary"
            @click="navigateTo('/circulation')"
          >
            <span><i class="pi pi-clipboard mr-2" />{{ $t('swipeWidgets.actionRequired.circulation') }}</span>
            <span class="text-surface-500">
              {{ $t('swipeWidgets.actionRequired.unconfirmed', { count: summary.circulation.unconfirmedCount }) }}
              <i class="pi pi-chevron-right ml-1 text-xs" />
            </span>
          </button>
          <ul class="mt-1 ml-6 list-disc text-xs text-surface-500">
            <li v-for="item in summary.circulation.items" :key="item.id" class="truncate">
              {{ item.title }}
            </li>
          </ul>
        </div>

        <!-- アンケート -->
        <div>
          <button
            type="button"
            class="flex w-full items-center justify-between text-left text-sm font-medium hover:text-primary"
            @click="navigateTo(surveyListPath)"
          >
            <span><i class="pi pi-file-edit mr-2" />{{ $t('swipeWidgets.actionRequired.survey') }}</span>
            <span class="text-surface-500">
              {{ $t('swipeWidgets.actionRequired.unanswered', { count: summary.survey.unansweredCount }) }}
              <i class="pi pi-chevron-right ml-1 text-xs" />
            </span>
          </button>
          <ul class="mt-1 ml-6 list-none text-xs text-surface-500">
            <li v-for="item in summary.survey.items" :key="item.id">
              <button
                type="button"
                class="w-full truncate text-left hover:text-primary"
                :data-testid="`action-required-survey-${item.id}`"
                @click="navigateTo(`/surveys/${item.id}?scope=${props.scopeType}&scopeId=${props.scopeId}`)"
              >
                {{ item.title }}
              </button>
            </li>
          </ul>
        </div>

        <!-- 出席確認 -->
        <div>
          <button
            type="button"
            class="flex w-full items-center justify-between text-left text-sm font-medium hover:text-primary"
            @click="navigateTo('/calendar')"
          >
            <span><i class="pi pi-check-square mr-2" />{{ $t('swipeWidgets.actionRequired.attendance') }}</span>
            <span class="text-surface-500">
              {{ $t('swipeWidgets.actionRequired.unanswered', { count: summary.attendance.unansweredCount }) }}
              <i class="pi pi-chevron-right ml-1 text-xs" />
            </span>
          </button>
          <ul class="mt-1 ml-6 list-disc text-xs text-surface-500">
            <li v-for="item in summary.attendance.items" :key="item.scheduleId" class="truncate">
              {{ item.eventTitle }}
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
