<script setup lang="ts">
/**
 * F03.12 §16 / Phase11 解散通知未送信リマインダー Widget。
 *
 * <p>主催者向けに「終了したのに解散通知が未送信のイベント」を一覧表示する。
 * BE 側 EventEndReminderBatchService から発火される {@code EVENT_DISMISSAL_REMINDER}
 * 通知の「アクション元」となるダッシュボード上の常設エリアでもある。</p>
 *
 * <p>Phase11 で {@code GET /api/v1/events/my-organizing/dismissal-reminders}
 * を新設し、placeholder だった本 Widget を実データ連携に切り替えた。</p>
 *
 * <p>表示条件:</p>
 * <ul>
 *   <li>BE が「自分が主催 / endAt &lt; now / 未解散」のイベントを抽出して返す</li>
 *   <li>0 件の時はカード自体を描画しない（条件付きレンダリング）</li>
 *   <li>各イベントについて {@link EventDismissalCard} を表示し、CTA から {@link DismissalDialog} を開く</li>
 * </ul>
 *
 * <p>取得失敗時は空表示にする。loading 中も空表示。
 * 本 Widget はあくまで「補助的な気付き」であり、主動線は通知センター経由の deep link なので、
 * 失敗を派手に表示する必要はない。</p>
 */
import EventDismissalCard from '~/components/event/dismissal/EventDismissalCard.vue'
import type { DismissalReminderTarget } from '~/types/care'

const targets = ref<DismissalReminderTarget[]>([])
const { fetchTargets } = useDismissalReminders()

/**
 * 主催未解散イベントを再取得する。
 *
 * <p>失敗時は targets を空のままにし、ダッシュボードに枠を表示しない。
 * ログだけ console.warn で残す（ユーザーの動線を阻害しないため）。</p>
 */
async function loadTargets(): Promise<void> {
  try {
    targets.value = await fetchTargets()
  } catch (e) {
    console.warn('[WidgetEventDismissalReminder] 主催未解散イベントの取得に失敗', e)
    targets.value = []
  }
}

function onSubmitted(): void {
  // 送信された対象を targets から除外する（最新を BE から取り直す）
  loadTargets()
}

onMounted(loadTargets)
</script>

<template>
  <div v-if="targets.length > 0" class="space-y-3" data-testid="widget-event-dismissal-reminder">
    <EventDismissalCard
      v-for="t in targets"
      :key="`${t.teamId}-${t.eventId}`"
      :team-id="t.teamId"
      :event-id="t.eventId"
      :event-name="t.eventName"
      @submitted="onSubmitted"
    />
  </div>
</template>
