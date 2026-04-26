<script setup lang="ts">
/**
 * F05.4 アンケート回答者・未回答者一覧
 *
 * 親コンポーネントから scopeType / scopeId / surveyId を受け取り、
 * GET /api/v1/{scope}/{id}/surveys/{surveyId}/respondents を取得して表示する。
 *
 * 督促 UI: canRemind=true の場合のみ未回答タブに「督促送信」ボタンを表示。
 *   - Backend API: POST /api/v1/surveys/{surveyId}/remind
 *   - 認可・回数制限・24時間クールダウン等は Backend 側で判定（403/400 を画面に表示）
 *
 * <!-- i18n: surveys.respondents.* (第三陣Dにて多言語化予定) -->
 */
import type { RespondentItem } from '~/types/survey'

const props = withDefaults(
  defineProps<{
    scopeType: 'TEAM' | 'ORGANIZATION'
    scopeId: number
    surveyId: number
    canRemind?: boolean
  }>(),
  { canRemind: false },
)

const { getRespondents, remindRespondents } = useSurveyApi()
const { error: showError, success: showSuccess } = useNotification()
const { relativeTime } = useRelativeTime()

const respondents = ref<RespondentItem[]>([])
const loading = ref(false)
const loadError = ref(false)
const reminding = ref(false)

// タブ: 'responded' | 'unresponded'
const activeTab = ref<'responded' | 'unresponded'>('responded')

const responded = computed(() => respondents.value.filter((r) => r.hasResponded))
const unresponded = computed(() => respondents.value.filter((r) => !r.hasResponded))
const totalCount = computed(() => respondents.value.length)
const respondedCount = computed(() => responded.value.length)

const visibleList = computed<RespondentItem[]>(() =>
  activeTab.value === 'responded' ? responded.value : unresponded.value,
)

async function loadRespondents() {
  loading.value = true
  loadError.value = false
  try {
    const res = await getRespondents(props.scopeType, props.scopeId, props.surveyId)
    respondents.value = res.data
  } catch {
    loadError.value = true
    showError('回答者一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function sendReminder() {
  if (reminding.value) return
  if (unresponded.value.length === 0) return
  reminding.value = true
  try {
    const res = await remindRespondents(props.surveyId)
    const remindedCount = res.data?.reminded_count ?? unresponded.value.length
    const remaining = res.data?.remaining_remind_quota
    const detail = remaining !== undefined ? `残り送信可能: ${remaining}回` : undefined
    showSuccess(`${remindedCount}名にリマインドを送信しました`, detail)
  } catch {
    // Backend が 400/403 を返す（上限超過・クールダウン中・権限不足）
    showError('督促の送信に失敗しました', '送信上限・クールダウン・権限をご確認ください')
  } finally {
    reminding.value = false
  }
}

function getInitial(name: string): string {
  const trimmed = name.trim()
  if (trimmed.length === 0) return '?'
  return trimmed.charAt(0).toUpperCase()
}

onMounted(() => loadRespondents())

defineExpose({ refresh: loadRespondents })
</script>

<template>
  <div class="flex flex-col gap-3">
    <!-- ヘッダー: サマリー + 再読込 -->
    <div class="flex items-center justify-between gap-2">
      <div class="text-sm">
        <span class="font-semibold text-surface-900">{{ respondedCount }}</span>
        <span class="text-surface-500"> / 全 {{ totalCount }} 名</span>
      </div>
      <Button
        icon="pi pi-refresh"
        severity="secondary"
        text
        rounded
        size="small"
        :disabled="loading"
        aria-label="再読込"
        @click="loadRespondents"
      />
    </div>

    <!-- タブ切替（PrimeVue SelectButton） -->
    <SelectButton
      v-model="activeTab"
      :options="[
        { label: `回答済み (${respondedCount})`, value: 'responded' },
        { label: `未回答 (${totalCount - respondedCount})`, value: 'unresponded' },
      ]"
      option-label="label"
      option-value="value"
      :allow-empty="false"
      class="self-start"
    />

    <!-- 督促ヒント / 督促ボタン（未回答タブのみ） -->
    <div v-if="activeTab === 'unresponded'" class="flex flex-col gap-2">
      <div
        v-if="canRemind"
        class="flex items-center justify-between gap-2 rounded-lg border border-surface-200 bg-surface-50 px-3 py-2"
      >
        <span class="text-xs text-surface-600">
          未回答の {{ unresponded.length }} 名にリマインド通知を送信できます（最大3回まで）
        </span>
        <Button
          label="督促送信"
          icon="pi pi-send"
          size="small"
          :loading="reminding"
          :disabled="unresponded.length === 0 || loading"
          @click="sendReminder"
        />
      </div>
      <div
        v-else
        class="rounded-lg border border-dashed border-surface-200 bg-surface-50 px-3 py-2 text-xs text-surface-500"
      >
        <i class="pi pi-info-circle mr-1" />
        督促通知は回答期限の 24 時間前に自動送信されます
      </div>
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 36px; height: 36px" />
    </div>

    <!-- エラー -->
    <div
      v-else-if="loadError"
      class="flex flex-col items-center gap-2 py-8 text-center"
    >
      <i class="pi pi-exclamation-triangle text-3xl text-red-500" />
      <p class="text-sm text-surface-500">回答者一覧を取得できませんでした</p>
      <Button label="再試行" icon="pi pi-refresh" size="small" @click="loadRespondents" />
    </div>

    <!-- リスト本体 -->
    <ul v-else-if="visibleList.length > 0" class="flex flex-col gap-1">
      <li
        v-for="item in visibleList"
        :key="item.userId"
        class="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 px-3 py-2"
      >
        <!-- アバター -->
        <Avatar
          v-if="item.avatarUrl"
          :image="item.avatarUrl"
          shape="circle"
          size="normal"
        />
        <Avatar
          v-else
          :label="getInitial(item.displayName)"
          shape="circle"
          size="normal"
          class="bg-primary-100 text-primary-700"
        />

        <!-- 名前 -->
        <span class="flex-1 truncate text-sm text-surface-800">
          {{ item.displayName }}
        </span>

        <!-- 回答済み: 相対時刻 / 未回答: バッジ -->
        <span
          v-if="item.hasResponded && item.respondedAt"
          class="text-xs text-surface-500"
        >
          {{ relativeTime(item.respondedAt) }}
        </span>
        <Badge v-else-if="item.hasResponded" value="回答済み" severity="success" />
        <Badge v-else value="未回答" severity="warn" />
      </li>
    </ul>

    <!-- 空状態 -->
    <div v-else class="flex flex-col items-center gap-2 py-10 text-center">
      <i class="pi pi-users text-3xl text-surface-300" />
      <p class="text-sm text-surface-400">
        {{ activeTab === 'responded' ? 'まだ回答者はいません' : '未回答者はいません' }}
      </p>
    </div>
  </div>
</template>
