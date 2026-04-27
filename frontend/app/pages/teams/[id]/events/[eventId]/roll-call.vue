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
  middleware: 'auth',
})

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const eventId = computed(() => Number(route.params.eventId))

const { candidates, sessionIds, loading, submitting, offlineQueued, loadCandidates, submit, loadSessions } =
  useRollCall(teamId, eventId)

const advanceNoticeApi = useAdvanceNoticeApi()
const advanceNotices = ref<AdvanceNoticeResponse[]>([])
const advanceNoticesLoading = ref(false)

const showHistory = ref(false)

async function reload(): Promise<void> {
  await Promise.all([loadCandidates(), loadAdvanceNotices()])
}

async function loadAdvanceNotices(): Promise<void> {
  advanceNoticesLoading.value = true
  try {
    advanceNotices.value = await advanceNoticeApi.getAdvanceNotices(
      teamId.value,
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
        :to="`/teams/${teamId}/events/${eventId}`"
        :label="$t('common.back')"
      />
      <h1 class="rc-page__title">
        {{ $t('event.rollCall.title') }}
      </h1>
    </header>

    <main class="rc-page__main">
      <PageLoading v-if="loading && candidates.length === 0" />
      <RollCallSheet
        v-else
        :team-id="teamId"
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
