<script setup lang="ts">
import type { SurveyActionItem, ScopeTabType } from '~/types/dashboard-scope'
import type { SurveyDetailResponse } from '~/types/survey'

/**
 * 要対応ウィジェット — アンケート回答モーダル。
 *
 * アンケートアイテムをクリックしたときにページ遷移せず、このモーダルで回答できる。
 * - 読み込み中はスピナー表示
 * - 取得成功 → SurveyResponseForm を埋め込んで回答
 * - 回答送信成功 → モーダル閉じ・件数1減
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §5
 */
const props = defineProps<{
  visible: boolean
  item: SurveyActionItem
  scopeType: ScopeTabType
  scopeId: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  submitted: []
}>()

const { getSurvey } = useSurveyApi()

const loading = ref(false)
const surveyDetail = ref<SurveyDetailResponse['data'] | null>(null)

function close() {
  emit('update:visible', false)
}

async function loadSurvey() {
  loading.value = true
  surveyDetail.value = null
  try {
    const scopeSegment = props.scopeType === 'TEAM' ? 'TEAM' : 'ORGANIZATION'
    const res = await getSurvey(scopeSegment, props.scopeId, props.item.id)
    surveyDetail.value = res.data
  } catch {
    // エラー時はnullのまま（フォームを表示しない）
    surveyDetail.value = null
  } finally {
    loading.value = false
  }
}

function onSubmitted() {
  emit('submitted')
  close()
}

watch(
  () => props.visible,
  (val) => {
    if (val) {
      loadSurvey()
    } else {
      surveyDetail.value = null
    }
  },
)
</script>

<template>
  <Dialog
    :visible="visible"
    :header="item.title"
    modal
    class="w-full max-w-xl"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="min-h-[8rem]">
      <div v-if="loading" class="flex items-center justify-center py-8">
        <i class="pi pi-spin pi-spinner text-2xl text-surface-400" />
      </div>

      <div v-else-if="!surveyDetail" class="flex items-center justify-center py-8">
        <span class="text-sm text-surface-500">
          {{ $t('swipeWidgets.actionRequired.surveyModal.loadError') }}
        </span>
      </div>

      <SurveyResponseForm
        v-else
        :survey="surveyDetail"
        :already-responded="false"
        :allow-multiple="false"
        @submitted="onSubmitted"
      />
    </div>

    <template v-if="!loading && !surveyDetail" #footer>
      <Button
        :label="$t('button.cancel')"
        severity="secondary"
        @click="close"
      />
    </template>
  </Dialog>
</template>
