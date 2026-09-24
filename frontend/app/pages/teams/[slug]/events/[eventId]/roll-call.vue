<script setup lang="ts">
import { computed, ref } from 'vue'
import RollCallSheet from '~/components/event/rollCall/RollCallSheet.vue'
import RollCallHistoryDrawer from '~/components/event/rollCall/RollCallHistoryDrawer.vue'
import type { AdvanceNoticeResponse, RollCallEntry } from '~/types/care'

/**
 * F03.12 §14 主催者点呼フルスクリーンページ。
 *
 * <p>少年団コーチがフィールドで片手操作することを想定し、
 * ヘッダーは戻るボタンと最低限の情報のみに抑え、画面全体を
 * 点呼シートに使う。EventDetail への組み込みは足軽 E が別途行う。</p>
 */

definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const eventId = computed(() => Number(route.params.eventId))

const { candidates, sessionIds, loading, submitting, offlineQueued, loadCandidates, submit, loadSessions } =
  useRollCall(teamSlug, eventId)

const advanceNoticeApi = useAdvanceNoticeApi()
const advanceNotices = ref<AdvanceNoticeResponse[]>([])
const advanceNoticesLoading = ref(false)

const showHistory = ref(false)

/**
 * 点呼候補の取得失敗は「対象者がいません」ではない。空状態へフォールバックせずエラー状態を出す。
 *
 * composable の `error` ref は submit / patchEntry など点呼候補取得以外の失敗でも
 * 更新されるため、それに相乗りすると「送信に失敗しただけ」で一覧ごとエラー状態に
 * 差し替わってしまう。取得専用の状態としてページ側で独立して持つ。
 */
const loadFailed = ref(false)

/**
 * 【2度目の検分差し戻し対応・CMP-260922-2045】候補取得（loadCandidates）と
 * 事前連絡取得（loadAdvanceNotices）それぞれの「最新の呼び出しか」を判定する世代番号。
 *
 * loadAdvanceNotices を loadCandidates から独立させた結果、初回の候補取得が
 * 失敗して再試行すると、初回・再試行の2回分の loadAdvanceNotices が同時に
 * 進行しうる。初回側が後から解決すると、既に再試行側が更新した最新の
 * advanceNotices を古い結果で上書きしてしまう（遅刻・欠席の件数が消える）。
 * 呼び出しごとに世代番号を発行し、自分より後の呼び出しが既に走っていたら
 * 自分の結果（成功・失敗・finally のいずれも）は state に反映しない。
 * 候補取得側の loadFailed も同じ理由で世代番号を持つ（loadCandidates 自体・
 * candidates.value の競合は useRollCall composable 内部の責務であり、他画面と
 * 共有されている composable のため本修正では手を入れず、既知の範囲として報告する）。
 */
let candidatesSeq = 0
let advanceNoticesSeq = 0

async function reload(): Promise<void> {
  const seq = ++candidatesSeq
  void loadAdvanceNotices()
  loadFailed.value = false
  try {
    await loadCandidates()
    if (seq === candidatesSeq) loadFailed.value = false
  } catch {
    if (seq === candidatesSeq) loadFailed.value = true
  }
}

async function loadAdvanceNotices(): Promise<void> {
  const seq = ++advanceNoticesSeq
  advanceNoticesLoading.value = true
  try {
    const res = await advanceNoticeApi.getAdvanceNotices(teamSlug.value, eventId.value)
    if (seq === advanceNoticesSeq) advanceNotices.value = res
  } catch {
    // サマリーは補助情報なので失敗しても点呼自体は継続できるよう静かに 0 件扱い
    // （ただし自分より後の呼び出しが既に走っていれば、その結果は上書きしない）
    if (seq === advanceNoticesSeq) advanceNotices.value = []
  } finally {
    if (seq === advanceNoticesSeq) advanceNoticesLoading.value = false
  }
}

async function onSubmit(payload: {
  entries: RollCallEntry[]
  notifyImmediately: boolean
}): Promise<void> {
  const result = await submit(payload.entries, payload.notifyImmediately)
  if (result || offlineQueued.value) {
    // 成功 or オフラインキュー積みなら一覧を再取得し、isAlreadyCheckedIn を更新
    await loadCandidates()
  }
}

async function onOpenHistory(): Promise<void> {
  showHistory.value = true
  await loadSessions()
}

onMounted(() => {
  void reload()
})
</script>

<template>
  <div class="rc-page">
    <header class="rc-page__header">
      <BackButton
        :to="`/teams/${teamSlug}/events/${eventId}`"
        :label="$t('common.back')"
      />
      <h1 class="rc-page__title">
        {{ $t('event.rollCall.title') }}
      </h1>
    </header>

    <main class="rc-page__main">
      <PageLoading v-if="loading && candidates.length === 0" />
      <DashboardErrorState
        v-else-if="loadFailed"
        testid="roll-call-error-state"
        @retry="reload"
      />
      <RollCallSheet
        v-else
        :team-id="teamSlug"
        :event-id="eventId"
        :candidates="candidates"
        :advance-notices="advanceNotices"
        :submitting="submitting"
        @submit="onSubmit"
        @open-history="onOpenHistory"
      />
    </main>

    <RollCallHistoryDrawer
      v-model:visible="showHistory"
      :session-ids="sessionIds"
      :loading="loading"
    />
  </div>
</template>

<style scoped>
.rc-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--app-header-height, 0px));
  min-height: 100vh;
}
.rc-page__header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
  background: var(--p-content-background, #fff);
}
.rc-page__title {
  font-size: 1.1rem;
  font-weight: 700;
  margin: 0;
}
.rc-page__main {
  flex: 1 1 auto;
  min-height: 0;
}
</style>
