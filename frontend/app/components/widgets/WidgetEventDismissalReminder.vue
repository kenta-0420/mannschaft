<script setup lang="ts">
/**
 * F03.12 Phase 10 §16 解散通知未送信リマインダー Widget。
 *
 * <p>主催者向けに「終了したのに解散通知が未送信のイベント」を一覧表示する。
 * BE 側 EventDismissalReminderJob から発火される {@code EVENT_DISMISSAL_REMINDER}
 * 通知の actionUrl と紐付けて表示するのが本来の姿だが、Phase 10 時点では
 * 主催イベントから dismissalStatus を逐次取得する API がまだ存在しないため、
 * この Widget は枠組みのみを提供し、内容は Phase 11 で確定させる。</p>
 *
 * <p>表示候補の抽出ロジック:</p>
 * <ul>
 *   <li>現在の主催イベントから {@code endAt < now} かつ {@code dismissalStatus.dismissed === false} のものを抽出</li>
 *   <li>各イベントについて {@link EventDismissalCard} を表示し、CTA から {@link DismissalDialog} を開く</li>
 * </ul>
 *
 * @todo Phase 11 で「主催イベントの dismissalStatus を一括取得する BE API」を追加し、
 *       本 Widget の {@code targets} を実データで埋める。
 *       それまでは空リストを返し、ダッシュボードに枠は表示しない（条件付きレンダリング）。
 */
import EventDismissalCard from '~/components/event/dismissal/EventDismissalCard.vue'

interface DismissalReminderTarget {
  teamId: number
  eventId: number
  eventName: string
}

const targets = ref<DismissalReminderTarget[]>([])

/**
 * Phase 11 で「主催イベントの dismissalStatus 一括取得 API」を呼ぶ予定。
 * 現状はバックエンドにエンドポイントが無いため、targets は空のままとする。
 *
 * 候補の抽出条件（実装時のメモ）:
 * - 自分が主催する team イベント
 * - endAt < now
 * - dismissalStatus.dismissed === false
 */
async function loadTargets(): Promise<void> {
  // TODO Phase 11: 主催イベントの dismissalStatus 一括取得 API を呼んで targets を埋める
  targets.value = []
}

function onSubmitted(): void {
  // 送信された対象を targets から除外する
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
