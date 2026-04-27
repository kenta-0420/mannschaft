<script setup lang="ts">
/**
 * F05.4 アンケート回答者一覧 — E2E テスト専用ページ。
 *
 * <p>SurveyRespondentsList コンポーネントは現時点で本番ページに組み込まれていない
 * （survey 詳細ページ {@code pages/surveys/[surveyId].vue} には未統合）。
 * SURVEY-003 督促送信 E2E は本コンポーネント単体の振る舞いを検証する必要があるため、
 * 本ページにマウントしてテスト経由でアクセスする。</p>
 *
 * <p>クエリパラメータ:</p>
 * <ul>
 *   <li>{@code surveyId}: アンケート ID（数値）</li>
 *   <li>{@code scopeType}: 'TEAM' | 'ORGANIZATION'</li>
 *   <li>{@code scopeId}: スコープ ID（数値）</li>
 *   <li>{@code canRemind}: 'true' で督促ボタン表示</li>
 * </ul>
 *
 * <p>本番ビルドでは middleware で 404 化することで意図しないアクセスを防ぐ。</p>
 *
 * <p>TODO: 本コンポーネントが survey 詳細ページに統合された後は、
 * 本ページを削除し E2E spec も実ページ経由のアサーションに書き換えること。</p>
 */
definePageMeta({
  layout: false,
  // 本番ビルドでは存在しないページ扱いにし、誤って公開されても 404 を返す。
  middleware: [
    () => {
      // import.meta.dev は dev サーバ起動時のみ true。本番ビルドでは false。
      if (!import.meta.dev) {
        throw createError({ statusCode: 404, statusMessage: 'Not Found' })
      }
    },
  ],
})

const route = useRoute()

const surveyId = computed(() => Number(route.query.surveyId ?? 0))
const scopeType = computed<'TEAM' | 'ORGANIZATION'>(() => {
  const v = String(route.query.scopeType ?? 'TEAM').toUpperCase()
  return v === 'ORGANIZATION' ? 'ORGANIZATION' : 'TEAM'
})
const scopeId = computed(() => Number(route.query.scopeId ?? 0))
const canRemind = computed(() => String(route.query.canRemind ?? 'false') === 'true')

const isReady = computed(
  () => Number.isFinite(surveyId.value) && surveyId.value > 0 && Number.isFinite(scopeId.value) && scopeId.value > 0,
)
</script>

<template>
  <div class="mx-auto max-w-3xl p-4" data-testid="test-survey-respondents-page">
    <SurveyRespondentsList
      v-if="isReady"
      :scope-type="scopeType"
      :scope-id="scopeId"
      :survey-id="surveyId"
      :can-remind="canRemind"
    />
  </div>
</template>
