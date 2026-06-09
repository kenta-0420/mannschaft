<script setup lang="ts">
// F08.10 警告/退場の理由コード選択（04_frontend_and_ux.md §G.2c・sports/01_soccer.md §8.3）。
// event_type に応じて許容コード群を提示（§5.3）: YELLOW_CARD→C1〜C8 / RED_CARD→S1〜S6 /
// SECOND_YELLOW→CS。色＋形状＋ラベル併用（色覚多様性・§G.12）。タップで選択＋補足note。任意。
import type { CardReasonCode, MatchEventType } from '~/types/match'

const props = defineProps<{
  /** カード種別（YELLOW_CARD / RED_CARD / SECOND_YELLOW） */
  cardType: MatchEventType
  modelValue: CardReasonCode | null
  note: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [code: CardReasonCode | null]
  'update:note': [note: string | null]
}>()

const { t } = useI18n()

const CAUTION: readonly CardReasonCode[] = ['C1', 'C2', 'C3', 'C4', 'C5', 'C6', 'C7', 'C8']
const SENDING_OFF: readonly CardReasonCode[] = ['S1', 'S2', 'S3', 'S4', 'S5', 'S6']

// 許容コード群（§5.3）。
const codes = computed<readonly CardReasonCode[]>(() => {
  if (props.cardType === 'YELLOW_CARD') return CAUTION
  if (props.cardType === 'SECOND_YELLOW') return ['CS']
  if (props.cardType === 'RED_CARD') return SENDING_OFF
  return []
})

// カードの色＋形（色だけに依存しない）。
const cardVisual = computed(() => {
  if (props.cardType === 'RED_CARD') {
    return { color: 'bg-red-600', label: t('match.card_type.RED'), icon: 'pi pi-stop-circle' }
  }
  if (props.cardType === 'SECOND_YELLOW') {
    return { color: 'bg-amber-400', label: t('match.card_type.SECOND_YELLOW'), icon: 'pi pi-clone' }
  }
  return { color: 'bg-amber-400', label: t('match.card_type.YELLOW'), icon: 'pi pi-stop' }
})

function select(code: CardReasonCode): void {
  emit('update:modelValue', props.modelValue === code ? null : code)
}

const noteModel = computed({
  get: () => props.note ?? '',
  set: (v: string) => emit('update:note', v || null),
})
</script>

<template>
  <div>
    <div class="mb-2 flex items-center gap-2">
      <span
        class="inline-flex h-5 w-4 items-center justify-center rounded-sm"
        :class="cardVisual.color"
        :aria-label="cardVisual.label"
      />
      <span class="text-sm font-medium">{{ cardVisual.label }}</span>
    </div>

    <p class="mb-1 text-xs text-surface-500">{{ t('match.live.card_reason.title') }}</p>
    <p class="mb-2 text-[11px] text-surface-400">{{ t('match.live.card_reason.skip_hint') }}</p>

    <div class="grid grid-cols-2 gap-2 sm:grid-cols-3">
      <button
        v-for="code in codes"
        :key="code"
        type="button"
        class="flex min-h-[2.75rem] items-center gap-2 rounded-lg border px-3 py-2 text-left text-sm transition"
        :class="
          modelValue === code
            ? 'border-primary bg-primary-50 text-primary'
            : 'border-surface-300 hover:border-primary-300'
        "
        @click="select(code)"
      >
        <span class="font-mono font-bold">{{ code }}</span>
        <span class="truncate text-xs">{{ t(`match.card_reason.${code}`) }}</span>
      </button>
    </div>

    <div class="mt-3 flex flex-col gap-1">
      <label class="text-xs text-surface-500">{{ t('match.live.flow.note_label') }}</label>
      <InputText
        v-model="noteModel"
        :placeholder="t('match.live.flow.note_placeholder')"
        class="w-full"
      />
    </div>
  </div>
</template>
