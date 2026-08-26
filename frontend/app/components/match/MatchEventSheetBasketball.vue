<script setup lang="ts">
// F08.10 バスケ用ボトムシート 3 タップフロー（sports/03_basketball.md §8.1〜§8.4）。
// プリセット（2P/3P/FT・リバウンド・ファウル・交代・その他）→ 選手 → 補助（アシスト/ファウル種別/理由コード）。
// 得点（2P/3P）はアシスト連鎖可（§8.2）。FT は通常アシストなし。ファウルは PF/TF 種別＋理由コード（§8.3）。
// 実 POST は live.vue（session）へ emit で委譲する（薄い UI 層）。カタログ駆動（プリセットは
// sportEventCatalog.BASKETBALL を正準・本コンポは並びを写経）。
import type { GridPlayer } from '~/composables/match/useMatchPlayerGrid'
import type { BasketballEventType, BasketballFoulCode, CatalogEventType } from '~/types/match'
import type { RecorderPlayer } from '~/composables/match/useMatchLiveRecorder'
import { SPORT_CATALOGS, type SportFlowKind } from '~/composables/match/sport/sportEventCatalog'

type Step = 'preset' | 'player' | 'player2' | 'aux'

const props = defineProps<{
  visible: boolean
  players: GridPlayer[]
}>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
  /** 得点（2P/3P/FT）。assist は 2P/3P でのみ任意紐付け。 */
  score: [payload: { scorer: RecorderPlayer; assist: RecorderPlayer | null; scoreType: BasketballEventType; note: string | null; assistNote: string | null }]
  /** リバウンド等の単発スタッツ（選手のみ）。 */
  stat: [payload: { player: RecorderPlayer; eventType: BasketballEventType; note: string | null }]
  /** ファウル（PF/TF）＋理由コード（任意）。 */
  foul: [payload: { player: RecorderPlayer; foulType: BasketballEventType; reasonCode: BasketballFoulCode | null; note: string | null }]
  /** 交代（OUT→IN）。 */
  substitution: [payload: { out: RecorderPlayer; in: RecorderPlayer }]
  /** その他（自由ラベル＋note）。 */
  other: [payload: { label: string; note: string | null }]
}>()

const { t } = useI18n()

const PRESETS = SPORT_CATALOGS.BASKETBALL.presets

const flow = ref<SportFlowKind | null>(null)
const step = ref<Step>('preset')
const player1 = ref<RecorderPlayer | null>(null)
const player2 = ref<RecorderPlayer | null>(null)
/** basket_score フローの得点種別（プリセットボタンの scoreEventType から決まる）。 */
const scoreType = ref<BasketballEventType>('FIELD_GOAL_2')
/** ファウル種別（PERSONAL_FOUL / TECHNICAL_FOUL）。 */
const foulType = ref<BasketballEventType>('PERSONAL_FOUL')
const reasonCode = ref<BasketballFoulCode | null>(null)
const note = ref('')
const note2 = ref('')
const otherLabel = ref('')

const open = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

/** ファウル種別ごとの許容理由コード（§5）。 */
const FOUL_CODES: Record<string, readonly BasketballFoulCode[]> = {
  PERSONAL_FOUL: ['PF', 'SF', 'OF', 'UF'],
  TECHNICAL_FOUL: ['TF'],
}
const foulCodes = computed<readonly BasketballFoulCode[]>(() => FOUL_CODES[foulType.value] ?? [])

function reset(): void {
  flow.value = null
  step.value = 'preset'
  player1.value = null
  player2.value = null
  scoreType.value = 'FIELD_GOAL_2'
  foulType.value = 'PERSONAL_FOUL'
  reasonCode.value = null
  note.value = ''
  note2.value = ''
  otherLabel.value = ''
  pickingLinkedPlayer.value = false
}

function close(): void {
  reset()
  open.value = false
}

function selectPreset(p: { flow: SportFlowKind; scoreEventType?: CatalogEventType }): void {
  flow.value = p.flow
  if (p.flow === 'basket_score' && p.scoreEventType) {
    scoreType.value = p.scoreEventType as BasketballEventType
  }
  step.value = p.flow === 'other' ? 'aux' : 'player'
}

function toPlayer(p: GridPlayer): RecorderPlayer {
  return { playerUserId: p.userId, playerName: p.name, jerseyNumber: p.jerseyNumber }
}

function onPlayer1(p: GridPlayer): void {
  player1.value = toPlayer(p)
  if (flow.value === 'substitution') step.value = 'player2'
  else if (flow.value === 'rebound') {
    // リバウンドは選手選択で即確定（補助なし）。
    emit('stat', { player: player1.value, eventType: 'REBOUND', note: null })
    close()
  } else step.value = 'aux'
}

function onPlayer2(p: GridPlayer): void {
  player2.value = toPlayer(p)
  if (flow.value === 'substitution' && player1.value) {
    emit('substitution', { out: player1.value, in: player2.value })
    close()
  } else {
    step.value = 'aux'
  }
}

function back(): void {
  if (step.value === 'player2') {
    player1.value = null
    step.value = 'player'
    return
  }
  if (step.value === 'aux') {
    step.value = flow.value === 'other' ? 'preset' : 'player'
    if (flow.value === 'other') flow.value = null
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
function confirmScore(withAssist: boolean): void {
  if (!player1.value) return
  emit('score', {
    scorer: player1.value,
    assist: withAssist ? player2.value : null,
    scoreType: scoreType.value,
    note: note.value || null,
    assistNote: note2.value || null,
  })
  close()
}

function confirmFoul(): void {
  if (!player1.value) return
  emit('foul', {
    player: player1.value,
    foulType: foulType.value,
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

// アシスト紐付けサブステップ（2P/3P でのみ）。
const pickingLinkedPlayer = ref(false)
function startPickAssist(): void {
  pickingLinkedPlayer.value = true
}
function onLinkedPlayer(p: GridPlayer): void {
  player2.value = toPlayer(p)
  pickingLinkedPlayer.value = false
}

function onFoulTypeChange(): void {
  reasonCode.value = null
}
function selectFoulCode(code: BasketballFoulCode): void {
  reasonCode.value = reasonCode.value === code ? null : code
}

watch(open, (v) => {
  if (!v) reset()
})

const titleKey = computed(() => {
  if (!flow.value) return 'match.live.title'
  if (flow.value === 'basket_score') return 'match.live.preset.field_goal_2'
  if (flow.value === 'rebound') return 'match.live.preset.rebound'
  if (flow.value === 'foul') return 'match.live.preset.foul'
  if (flow.value === 'substitution') return 'match.live.preset.substitution'
  return 'match.live.preset.other'
})

/** FT はアシスト連鎖を出さない（§8.1）。 */
const allowAssist = computed(() => scoreType.value !== 'FREE_THROW')
</script>

<template>
  <Drawer
    v-model:visible="open"
    position="bottom"
    :header="t(titleKey)"
    class="!h-auto max-h-[85vh]"
  >
    <!-- プリセット選択（カタログ駆動・§8.1） -->
    <div v-if="step === 'preset'" class="grid grid-cols-2 gap-3">
      <Button
        v-for="preset in PRESETS"
        :key="preset.labelKey + (preset.scoreEventType ?? '')"
        class="!min-h-[3.5rem]"
        :class="preset.flow === 'other' ? 'col-span-2 !min-h-[3rem]' : ''"
        :severity="preset.severity"
        :outlined="preset.flow === 'other'"
        :label="t(preset.labelKey)"
        :icon="preset.icon"
        @click="selectPreset(preset)"
      />
    </div>

    <!-- 選手選択 -->
    <div v-else-if="step === 'player' || step === 'player2'">
      <p class="mb-2 text-sm text-surface-600">
        {{ step === 'player2'
          ? t('match.live.flow.select_in')
          : flow === 'basket_score' ? t('match.live.flow.select_scorer')
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
      <!-- 得点（2P/3P/FT） -->
      <template v-if="flow === 'basket_score'">
        <p class="mb-3 text-sm font-medium">{{ t(`match.event_type.${scoreType}`) }}</p>
        <div class="mb-3 flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ t('match.live.flow.note_label') }}</label>
          <InputText v-model="note" :placeholder="t('match.live.flow.note_placeholder')" class="w-full" />
        </div>
        <div v-if="!pickingLinkedPlayer">
          <div v-if="player2" class="mb-2 rounded bg-surface-50 p-2 text-sm">
            {{ t('match.live.flow.select_assist') }}: {{ player2.playerName }}
          </div>
          <div class="flex flex-wrap gap-2">
            <Button
              v-if="allowAssist && !player2"
              outlined
              :label="t('match.live.flow.add_assist')"
              icon="pi pi-plus"
              @click="startPickAssist"
            />
            <Button :label="t('match.live.flow.confirm')" icon="pi pi-check" @click="confirmScore(!!player2)" />
            <Button text :label="t('match.live.flow.back')" @click="back" />
          </div>
        </div>
        <div v-else>
          <p class="mb-2 text-sm text-surface-600">{{ t('match.live.flow.select_assist') }}</p>
          <MatchPlayerGrid :players="players" select-mode @select="onLinkedPlayer" />
        </div>
      </template>

      <!-- ファウル（PF/TF＋理由コード・§8.3） -->
      <template v-else-if="flow === 'foul'">
        <div class="mb-3 flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ t('match.live.flow.foul_type_label') }}</label>
          <SelectButton
            v-model="foulType"
            :options="[
              { value: 'PERSONAL_FOUL', label: t('match.foul_type.PERSONAL_FOUL') },
              { value: 'TECHNICAL_FOUL', label: t('match.foul_type.TECHNICAL_FOUL') },
            ]"
            option-label="label"
            option-value="value"
            @change="onFoulTypeChange"
          />
        </div>

        <p class="mb-1 text-xs text-surface-500">{{ t('match.live.card_reason.title') }}</p>
        <p class="mb-2 text-[11px] text-surface-400">{{ t('match.live.card_reason.skip_hint') }}</p>
        <div class="grid grid-cols-2 gap-2 sm:grid-cols-3">
          <button
            v-for="code in foulCodes"
            :key="code"
            type="button"
            class="flex min-h-[2.75rem] items-center gap-2 rounded-lg border px-3 py-2 text-left text-sm transition"
            :class="
              reasonCode === code
                ? 'border-primary bg-primary-50 text-primary'
                : 'border-surface-300 hover:border-primary-300'
            "
            @click="selectFoulCode(code)"
          >
            <span class="font-mono font-bold">{{ code }}</span>
            <span class="truncate text-xs">{{ t(`match.card_reason.${code}`) }}</span>
          </button>
        </div>

        <div class="mt-3 flex flex-col gap-1">
          <label class="text-xs text-surface-500">{{ t('match.live.flow.note_label') }}</label>
          <InputText v-model="note" :placeholder="t('match.live.flow.note_placeholder')" class="w-full" />
        </div>

        <div class="mt-3 flex flex-wrap gap-2">
          <Button :label="t('match.live.flow.confirm')" icon="pi pi-check" @click="confirmFoul" />
          <Button text :label="t('match.live.flow.skip')" @click="confirmFoul" />
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
