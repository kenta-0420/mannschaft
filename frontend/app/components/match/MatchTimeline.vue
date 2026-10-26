<script setup lang="ts">
// F08.10 タイムライン（04_frontend_and_ux.md §G.2 / §G.2b / §G.2d / §G.10・sports/01_soccer.md §8.4）。
// 時系列リスト・連鎖（GOAL⇔ASSIST）の束ね表示・理由コード表示・記録チーム識別インジケーター・
// タップで編集/削除。色＋形状＋ラベル併用（色覚多様性）。
import type { MatchEventResponse, MatchEventType } from '~/types/match'

const props = defineProps<{
  events: MatchEventResponse[]
  /** 自チーム側（HOME/AWAY）。これと teamSide 不一致なら相手記録扱い。 */
  ownTeamSide: 'HOME' | 'AWAY'
}>()

const emit = defineEmits<{
  select: [event: MatchEventResponse]
  delete: [event: MatchEventResponse]
}>()

const { t } = useI18n()

// linked_event_id でペアになるイベントは「主」側に束ねて表示する。
// 束ね判定: 各イベントが linkedEventId を持つ場合、その相手を子として束ねる。
// 重複表示を防ぐため、既に他イベントの子として束ねられた ID は単独行から除外する。
interface TimelineRow {
  primary: MatchEventResponse
  linked: MatchEventResponse | null
}

const rows = computed<TimelineRow[]>(() => {
  const byId = new Map<string, MatchEventResponse>()
  for (const e of props.events) if (e.id) byId.set(e.id, e)

  const consumed = new Set<string>()
  const result: TimelineRow[] = []

  for (const e of props.events) {
    if (e.id && consumed.has(e.id)) continue
    const linkedId = e.linkedEventId
    const linked = linkedId ? byId.get(linkedId) ?? null : null
    if (linked?.id) {
      consumed.add(linked.id)
    }
    result.push({ primary: e, linked })
  }
  return result
})

function isOpponent(e: MatchEventResponse): boolean {
  return e.teamSide != null && e.teamSide !== props.ownTeamSide
}

function eventLabel(type: MatchEventType | undefined): string {
  return type ? t(`match.event_type.${type}`) : ''
}

function playerLabel(e: MatchEventResponse): string {
  const num = e.jerseyNumber != null ? `${e.jerseyNumber} ` : ''
  return `${num}${e.playerName ?? ''}`.trim()
}

function isCard(type: MatchEventType | undefined): boolean {
  return type === 'YELLOW_CARD' || type === 'RED_CARD' || type === 'SECOND_YELLOW'
}

function cardColor(type: MatchEventType | undefined): string {
  if (type === 'RED_CARD') return 'bg-red-600'
  return 'bg-amber-400'
}

function minuteLabel(e: MatchEventResponse): string {
  if (e.minute == null) return ''
  const stop = e.stoppageMinute ? `+${e.stoppageMinute}` : ''
  return `${e.minute}${stop}${t('match.live.timeline.minute_suffix')}`
}
</script>

<template>
  <div>
    <h3 class="mb-2 text-sm font-semibold text-surface-600">{{ t('match.live.timeline.title') }}</h3>

    <p v-if="rows.length === 0" class="rounded-lg bg-surface-50 p-4 text-sm text-surface-500">
      {{ t('match.live.timeline.empty') }}
    </p>

    <ul v-else class="flex flex-col gap-2">
      <li
        v-for="row in rows"
        :key="row.primary.id ?? `${row.primary.eventType}-${row.primary.minute}`"
        class="rounded-lg border p-3 text-sm"
        :class="isOpponent(row.primary) ? 'border-surface-200 bg-surface-50' : 'border-surface-300 bg-surface-0'"
      >
        <div class="flex items-start gap-3">
          <!-- 記録チーム識別インジケーター -->
          <span
            class="mt-0.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[10px] font-bold"
            :class="isOpponent(row.primary) ? 'bg-surface-200 text-surface-600' : 'bg-primary-100 text-primary-700'"
            :title="isOpponent(row.primary) ? t('match.live.timeline.opponent') : t('match.live.timeline.self')"
          >
            {{ isOpponent(row.primary) ? t('match.live.timeline.opponent') : t('match.live.timeline.self') }}
          </span>

          <button
            type="button"
            class="flex-1 text-left"
            @click="emit('select', row.primary)"
          >
            <!-- minute -->
            <span class="mr-2 font-mono text-xs text-surface-400">{{ minuteLabel(row.primary) }}</span>

            <!-- カード（色＋形状＋コード＋ラベル） -->
            <template v-if="isCard(row.primary.eventType)">
              <span
                class="mr-1 inline-block h-4 w-3 rounded-sm align-middle"
                :class="cardColor(row.primary.eventType)"
                :aria-label="eventLabel(row.primary.eventType)"
              />
              <span class="font-medium">{{ eventLabel(row.primary.eventType) }}</span>
              <span v-if="row.primary.cardReasonCode" class="ml-1 font-mono text-xs text-surface-500">
                {{ row.primary.cardReasonCode }}
                {{ t(`match.card_reason.${row.primary.cardReasonCode}`) }}
              </span>
            </template>

            <!-- その他イベント -->
            <template v-else>
              <span class="font-medium">
                {{ row.primary.eventType === 'OTHER' && row.primary.customLabel
                  ? row.primary.customLabel
                  : eventLabel(row.primary.eventType) }}
              </span>
            </template>

            <span v-if="playerLabel(row.primary)" class="ml-1 text-surface-600">
              （{{ playerLabel(row.primary) }}）
            </span>
            <span v-if="row.primary.note" class="ml-1 text-xs text-surface-400">
              — {{ row.primary.note }}
            </span>

            <!-- 連鎖の相手側（束ね表示・例「7番 アシスト ⤵ / 9番 得点」） -->
            <div v-if="row.linked" class="mt-1 flex items-center gap-1 pl-4 text-xs text-surface-500">
              <i class="pi pi-arrow-down-right text-[10px]" />
              <span>{{ eventLabel(row.linked.eventType) }}</span>
              <span v-if="playerLabel(row.linked)">（{{ playerLabel(row.linked) }}）</span>
              <span v-if="row.linked.note">— {{ row.linked.note }}</span>
            </div>
          </button>

          <button
            v-if="!isOpponent(row.primary)"
            type="button"
            class="shrink-0 rounded p-1 text-surface-400 transition hover:text-red-500"
            :aria-label="t('match.live.timeline.delete')"
            @click="emit('delete', row.primary)"
          >
            <i class="pi pi-trash text-sm" />
          </button>
        </div>
      </li>
    </ul>
  </div>
</template>
