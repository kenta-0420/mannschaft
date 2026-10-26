<script setup lang="ts">
// F08.10 ライブ観戦ビュー（read-only spectator・04_frontend_and_ux.md §G.17 / 07_realtime_spectator.md §J）。
//
// 観戦者（記録権限なし）が `/topic/matches/{matchId}/live` を STOMP 購読し、記録者の HTTP 書き込みが
// コミット後に配信される差分でスコア/タイムラインをリアルタイム表示する。**記録 UI を一切持たない**
// （read-only・書き込みは記録者の HTTP のみ・07 §J.1）。
//
// - 初期スナップショット（HTTP）→ topic 差分追従・再接続再取得は useMatchLiveSpectator が担う（07 §J.4）。
// - 接続状態インジケーター・「観戦のみ」バッジ・オフライン明示は本コンポーネントの責務（§G.17）。
import { useMatchLiveSpectator } from '~/composables/match/useMatchLiveSpectator'

const props = defineProps<{
  orgId: number | null
  teamId: number | null
  matchId: string
  ownTeamSide: 'HOME' | 'AWAY'
  opponentName: string | null
}>()

const { t } = useI18n()

// ctx は ref で渡す（composable 内で .value 参照・親の解決完了を追従する）。
const orgIdRef = computed(() => props.orgId)
const teamIdRef = computed(() => props.teamId)

const spectator = useMatchLiveSpectator({
  orgId: orgIdRef,
  teamId: teamIdRef,
  matchId: props.matchId,
})

const { snapshot, connectionState, hasSnapshot } = spectator

const isDenied = computed(() => connectionState.value === 'DENIED')
const isOffline = computed(() => connectionState.value === 'OFFLINE')

function onOnline(): void {
  void spectator.resync()
}
function onOffline(): void {
  // 端末オフライン: 即座に OFFLINE を示す（HTTP も WS も不通になる・§G.17）。
  if (connectionState.value !== 'DENIED') connectionState.value = 'OFFLINE'
}

onMounted(() => {
  void spectator.start()
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
})

onBeforeUnmount(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  spectator.stop()
})
</script>

<template>
  <div>
    <!-- 接続状態インジケーター＋「観戦のみ・記録権限なし」バッジ（併置・§G.17） -->
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <MatchConnectionIndicator :state="connectionState" />
      <span
        class="inline-flex items-center gap-1.5 rounded-full bg-surface-100 px-2.5 py-1 text-xs font-medium text-surface-600"
      >
        <i class="pi pi-eye text-[0.7rem]" />
        {{ t('match.live.spectator.read_only_badge') }}
      </span>
    </div>

    <!-- 購読拒否（可視性なし・F00／07 §J.3.1） -->
    <div
      v-if="isDenied"
      class="rounded-lg border border-surface-200 bg-surface-50 p-6 text-center"
    >
      <i class="pi pi-lock mb-2 text-2xl text-surface-400" />
      <p class="text-sm font-medium text-surface-700">{{ t('match.live.spectator.denied.title') }}</p>
      <p class="mt-1 text-xs text-surface-500">{{ t('match.live.spectator.denied.detail') }}</p>
    </div>

    <template v-else>
      <!-- オフライン明示バナー（HTTP も不通＝最終スナップショットを表示し「最新でない可能性」を明示・§G.17） -->
      <div
        v-if="isOffline && hasSnapshot"
        class="mb-3 flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 p-2 text-xs text-amber-800"
        role="alert"
      >
        <i class="pi pi-exclamation-triangle mt-0.5" />
        <span>{{ t('match.live.spectator.offline_banner') }}</span>
      </div>

      <MatchSpectatorScoreboard
        :home-score="snapshot.homeScore"
        :away-score="snapshot.awayScore"
        :home-penalty-score="snapshot.homePenaltyScore"
        :away-penalty-score="snapshot.awayPenaltyScore"
        :opponent-name="opponentName"
        :status="snapshot.status"
        class="mb-3"
      />

      <!-- タイムライン（read-only＝select/delete は配線しない＝記録者の編集導線を出さない） -->
      <MatchTimeline :events="snapshot.events" :own-team-side="ownTeamSide" />
    </template>
  </div>
</template>
