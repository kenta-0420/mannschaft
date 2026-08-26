<script setup lang="ts">
// F08.10 ボトムシート 3 タップフロー（04_frontend_and_ux.md §G.2・sports/01_soccer.md §8.1〜8.3）。
// プリセット（得点/アシスト/警告退場/交代/その他）→ 選手 → 補助（アシスト/カード色/理由コード）。
// 各ステップに 確定/スキップ/キャンセル(戻る)。交代 OUT→IN 中キャンセルは操作全体を破棄。
// 実 POST は live.vue（recorder）へ emit で委譲する（薄い UI 層）。
import type { GridPlayer } from '~/composables/match/useMatchPlayerGrid'
import type { CardReasonCode, MatchEventType } from '~/types/match'
import type { RecorderPlayer } from '~/composables/match/useMatchLiveRecorder'

type Flow = 'goal' | 'assist' | 'card' | 'substitution' | 'other'
type Step = 'preset' | 'player' | 'player2' | 'aux'

const props = defineProps<{
  visible: boolean
  players: GridPlayer[]
}>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
  goal: [payload: { scorer: RecorderPlayer; assist: RecorderPlayer | null; goalType: 'GOAL' | 'PENALTY_GOAL' | 'OWN_GOAL'; note: string | null; assistNote: string | null }]
  assist: [payload: { assist: RecorderPlayer; scorer: RecorderPlayer | null; note: string | null }]
  card: [payload: { player: RecorderPlayer; cardType: MatchEventType; reasonCode: CardReasonCode | null; note: string | null }]
  substitution: [payload: { out: RecorderPlayer; in: RecorderPlayer }]
  other: [payload: { label: string; note: string | null }]
}>()

const { t } = useI18n()

const flow = ref<Flow | null>(null)
const step = ref<Step>('preset')
const player1 = ref<RecorderPlayer | null>(null)
const player2 = ref<RecorderPlayer | null>(null)
const goalType = ref<'GOAL' | 'PENALTY_GOAL' | 'OWN_GOAL'>('GOAL')
const cardType = ref<MatchEventType>('YELLOW_CARD')
const reasonCode = ref<CardReasonCode | null>(null)
const note = ref('')
const note2 = ref('')
const otherLabel = ref('')

const open = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

function reset(): void {
  flow.value = null
  step.value = 'preset'
  player1.value = null
  player2.value = null
  goalType.value = 'GOAL'
  cardType.value = 'YELLOW_CARD'
  reasonCode.value = null
  note.value = ''
  note2.value = ''
  otherLabel.value = ''
}

function close(): void {
  reset()
  open.value = false
}

function selectFlow(f: Flow): void {
  flow.value = f
  step.value = f === 'other' ? 'aux' : 'player'
}

function toPlayer(p: GridPlayer): RecorderPlayer {
  return { playerUserId: p.userId, playerName: p.name, jerseyNumber: p.jerseyNumber }
}

function onPlayer1(p: GridPlayer): void {
  player1.value = toPlayer(p)
  if (flow.value === 'substitution') step.value = 'player2'
  else step.value = 'aux'
}

function onPlayer2(p: GridPlayer): void {
  player2.value = toPlayer(p)
  if (flow.value === 'substitution' && player1.value) {
    // 交代は OUT+IN 揃った時点で確定（理由コード等の補助は無い）。
    emit('substitution', { out: player1.value, in: player2.value })
    close()
  } else {
    step.value = 'aux'
  }
}

// 戻る: ステップを 1 段戻す。交代 OUT→IN 中のキャンセルは操作全体を破棄（§各ステップ UI 仕様）。
function back(): void {
  if (step.value === 'player2') {
    // 中途半端な SUB_OUT を残さないため flow ごと破棄してプリセットへ戻す。
    player1.value = null
    step.value = 'player'
    return
  }
  if (step.value === 'aux') {
    step.value = flow.value === 'other' ? 'preset' : 'player'
    return
  }
  if (step.value === 'player') {
    step.value = 'preset'
    flow.value = null
    return
  }
  close()
}

// === 確定処理（aux ステップ） ===
function confirmGoal(withAssist: boolean): void {
  if (!player1.value) return
  emit('goal', {
    scorer: player1.value,
    assist: withAssist ? player2.value : null,
    goalType: goalType.value,
    note: note.value || null,
    assistNote: note2.value || null,
  })
  close()
}

function confirmAssist(): void {
  if (!player1.value) return
  emit('assist', { assist: player1.value, scorer: player2.value, note: note.value || null })
  close()
}

function confirmCard(): void {
  if (!player1.value) return
  emit('card', {
    player: player1.value,
    cardType: cardType.value,
    reasonCode: reasonCode.value,
    note: note.value || null,
  })
  close()
}

function confirmOther(): void {
  const label = otherLabel.value.trim()
  if (!label) return
  emit('other', { label, note: note.value || null })
  close()
}

// アシスト/得点 紐付けサブステップ（得点・アシストフローで連鎖相手を選ぶ）。
const pickingLinkedPlayer = ref(false)
function startPickAssist(): void { pickingLinkedPlayer.value = true }
function onLinkedPlayer(p: GridPlayer): void {
  player2.value = toPlayer(p)
  pickingLinkedPlayer.value = false
}

watch(open, (v) => { if (!v) reset() })

const titleKey = computed(() =>
  flow.value ? `match.live.preset.${flow.value}` : 'match.live.title',
)
</script>

<template>
  <Drawer
    v-model:visible="open"
    position="bottom"
    :header="t(titleKey)"
    class="!h-auto max-h-[85vh]"
  >
    <!-- プリセット選択 -->
    <div v-if="step === 'preset'" class="grid grid-cols-2 gap-3">
      <Button class="!min-h-[3.5rem]" :label="t('match.live.preset.goal')" icon="pi pi-flag" @click="selectFlow('goal')" />
      <Button class="!min-h-[3.5rem]" severity="info" :label="t('match.live.preset.assist')" icon="pi pi-share-alt" @click="selectFlow('assist')" />
      <Button class="!min-h-[3.5rem]" severity="warn" :label="t('match.live.preset.card')" icon="pi pi-stop" @click="selectFlow('card')" />
      <Button class="!min-h-[3.5rem]" severity="secondary" :label="t('match.live.preset.substitution')" icon="pi pi-sync" @click="selectFlow('substitution')" />
      <Button class="col-span-2 !min-h-[3rem]" severity="secondary" outlined :label="t('match.live.preset.other')" icon="pi pi-ellipsis-h" @click="selectFlow('other')" />
    </div>

    <!-- 選手選択（player / player2） -->
    <div v-else-if="step === 'player' || step === 'player2'">
      <p class="mb-2 text-sm text-surface-600">
        {{ step === 'player2'
          ? t('match.live.flow.select_in')
          : flow === 'goal' ? t('match.live.flow.select_scorer')
          : flow === 'assist' ? t('match.live.flow.select_assist')
          : flow === 'substitution' ? t('match.live.flow.select_out')
          : t('match.live.flow.select_player') }}
      </p>
      <MatchPlayerGrid
        :players="players"
        select-mode
        @select="step === 'player2' ? onPlayer2($event) : onPlayer1($event)"
      />
      <div class="mt-3 flex justify-between">
        <Button text :label="t('match.live.flow.back')" icon="pi pi-arrow-left" @click="back" />
      </div>
    </div>

    <!-- 補助ステップ -->
    <div v-else-if="step === 'aux'">
      <!-- 得点 -->
      <template v-if="flow === 'goal'">
        <div class="mb-3 flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ t('match.live.flow.goal_type_label') }}</label>
          <SelectButton
            v-model="goalType"
            :options="[
              { value: 'GOAL', label: t('match.live.flow.goal_type.GOAL') },
              { value: 'PENALTY_GOAL', label: t('match.live.flow.goal_type.PENALTY_GOAL') },
              { value: 'OWN_GOAL', label: t('match.live.flow.goal_type.OWN_GOAL') },
            ]"
            option-label="label"
            option-value="value"
          />
        </div>
        <div class="mb-3 flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ t('match.live.flow.note_label') }}</label>
          <InputText v-model="note" :placeholder="t('match.live.flow.note_placeholder')" class="w-full" />
        </div>
        <div v-if="!pickingLinkedPlayer">
          <div v-if="player2" class="mb-2 rounded bg-surface-50 p-2 text-sm">
            {{ t('match.live.flow.select_assist') }}: {{ player2.playerName }}
          </div>
          <div class="flex flex-wrap gap-2">
            <Button v-if="!player2" outlined :label="t('match.live.flow.add_assist')" icon="pi pi-plus" @click="startPickAssist" />
            <Button :label="t('match.live.flow.confirm')" icon="pi pi-check" @click="confirmGoal(!!player2)" />
            <Button text :label="t('match.live.flow.back')" @click="back" />
          </div>
        </div>
        <div v-else>
          <p class="mb-2 text-sm text-surface-600">{{ t('match.live.flow.select_assist') }}</p>
          <MatchPlayerGrid :players="players" select-mode @select="onLinkedPlayer" />
        </div>
      </template>

      <!-- アシスト起点 -->
      <template v-else-if="flow === 'assist'">
        <div class="mb-3 flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ t('match.live.flow.note_label') }}</label>
          <InputText v-model="note" :placeholder="t('match.live.flow.note_placeholder')" class="w-full" />
        </div>
        <div v-if="player2" class="mb-2 rounded bg-surface-50 p-2 text-sm">
          {{ t('match.live.flow.select_scorer') }}: {{ player2.playerName }}
        </div>
        <div v-if="!pickingLinkedPlayer" class="flex flex-wrap gap-2">
          <Button v-if="!player2" outlined :label="t('match.live.flow.to_goal')" icon="pi pi-arrow-right" @click="startPickAssist" />
          <Button :label="t('match.live.flow.confirm')" icon="pi pi-check" @click="confirmAssist" />
          <Button text :label="t('match.live.flow.back')" @click="back" />
        </div>
        <div v-else>
          <p class="mb-2 text-sm text-surface-600">{{ t('match.live.flow.select_scorer') }}</p>
          <MatchPlayerGrid :players="players" select-mode @select="onLinkedPlayer" />
        </div>
      </template>

      <!-- カード -->
      <template v-else-if="flow === 'card'">
        <div class="mb-3 flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ t('match.live.flow.card_color_label') }}</label>
          <SelectButton
            v-model="cardType"
            :options="[
              { value: 'YELLOW_CARD', label: t('match.live.flow.card_yellow') },
              { value: 'RED_CARD', label: t('match.live.flow.card_red') },
              { value: 'SECOND_YELLOW', label: t('match.live.flow.card_second_yellow') },
            ]"
            option-label="label"
            option-value="value"
            @change="reasonCode = null"
          />
        </div>
        <MatchCardReasonPicker
          v-model="reasonCode"
          :card-type="cardType"
          :note="note || null"
          @update:note="note = $event ?? ''"
        />
        <div class="mt-3 flex flex-wrap gap-2">
          <Button :label="t('match.live.flow.confirm')" icon="pi pi-check" @click="confirmCard" />
          <Button text :label="t('match.live.flow.skip')" @click="confirmCard" />
          <Button text :label="t('match.live.flow.back')" @click="back" />
        </div>
      </template>

      <!-- その他 -->
      <template v-else-if="flow === 'other'">
        <div class="mb-3 flex flex-col gap-1">
          <InputText v-model="otherLabel" :placeholder="t('match.live.flow.other_label_placeholder')" class="w-full" />
        </div>
        <div class="mb-3 flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ t('match.live.flow.note_label') }}</label>
          <InputText v-model="note" :placeholder="t('match.live.flow.note_placeholder')" class="w-full" />
        </div>
        <div class="flex flex-wrap gap-2">
          <Button :label="t('match.live.flow.confirm')" icon="pi pi-check" :disabled="!otherLabel.trim()" @click="confirmOther" />
          <Button text :label="t('match.live.flow.back')" @click="back" />
        </div>
      </template>
    </div>
  </Drawer>
</template>
