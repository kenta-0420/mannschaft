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

async function reload(): Promise<void> {
  // 【検分差し戻し対応・CMP-260922-2045】loadCandidates と loadAdvanceNotices を
  // allSettled で両方の完了を待ってから loadFailed を決めていたが、それだと
  // 補助情報（事前連絡）が遅い／止まっている間、候補取得の成否が画面に反映されない
  // （初回: 候補が失敗していても補助情報待ちの間は空状態のまま／再試行: 候補が
  // 成功してもエラー画面から戻れない）。loadFailed は候補取得が終わった時点で
  // 即座に反映し、補助情報の完了を待たない。補助情報は従来どおり並行して取得する
  // （失敗しても静かに0件扱いという既存方針は変えない）。
  void loadAdvanceNotices()
  loadFailed.value = false
  try {
    await loadCandidates()
  } catch {
    loadFailed.value = true
  }
}

async function loadAdvanceNotices(): Promise<void> {
  advanceNoticesLoading.value = true
  try {
    advanceNotices.value = await advanceNoticeApi.getAdvanceNotices(
      teamSlug.value,
      eventId.value,
    )
  } catch {
    // サマリーは補助情報なので失敗しても点呼自体は継続できるよう静かに 0 件扱い
    advanceNotices.value = []
  } finally {
    advanceNoticesLoading.value = false
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
