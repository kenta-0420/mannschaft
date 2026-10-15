<script setup lang="ts">
/**
 * レイヤー凡例・フィルタのチップ列（F03.19 §6.4）。
 *
 * - レイヤーを**色ドット＋名前**のチップとして並べる。選択中は塗り、非選択は輪郭のみ
 *   （色の情報を残したまま on/off が分かる）。
 * - 予定0件のレイヤーもチップとして常に出す（AC-02）。表示対象は呼び出し元が渡す
 *   `options`（`allScopeOptions` = レイヤー一覧由来）であり、予定の有無に依存しない。
 * - `FILTER_OVERFLOW` 件を超えたら MultiSelect へ畳む。**畳んだ場合も「N件のレイヤー」と
 *   件数を表示**し、レイヤーの存在自体は隠さない（P3）。
 * - チップの右クリック／長押し／「…」ボタンで色変更ポップオーバーを開く。
 *   フォールバックチップ（レイヤー一覧に無いスコープ・§5.2.1）では開かない。
 * - **色だけに識別を担わせない**（§3.3 の色覚多様性配慮）。チップは必ず名前を併記する。
 */
import type { ScopeOption } from '~/composables/useMyCalendarData'
import { FILTER_OVERFLOW } from '~/composables/useMyCalendarData'
import { CALENDAR_LAYER_PALETTE, contrastRatio } from '~/utils/calendarLayerPalette'

const props = defineProps<{
  options: ScopeOption[]
  selected: string[]
}>()

const emit = defineEmits<{
  (e: 'toggle', value: string): void
  (e: 'update:selected', value: string[]): void
  (e: 'color', payload: { scopeType: string; scopeId: number; color: string }): void
  (e: 'reset-color', payload: { scopeType: string; scopeId: number }): void
}>()

const { t } = useI18n()

/** 長押し（500ms）でポップオーバーを開くためのタイマー。 */
const LONG_PRESS_MS = 500
let longPressTimer: ReturnType<typeof setTimeout> | null = null

/** 色変更ポップオーバーを開いているチップの value（null は閉じている）。 */
const openColorFor = ref<string | null>(null)

const isCollapsed = computed(() => props.options.length > FILTER_OVERFLOW)

const multiSelectValue = computed({
  get: () => [...props.selected],
  set: (vals: string[]) => emit('update:selected', vals),
})

function isSelected(value: string): boolean {
  return props.selected.includes(value)
}

/** 色変更を開けるチップか（レイヤー一覧由来かつ数値 scopeId を持つもののみ）。 */
function canEditColor(option: ScopeOption): boolean {
  return !option.isFallback && option.layerScopeId !== undefined
}

/**
 * 塗りチップの文字色。パレット色なら §3.3 の実測済み文字色を使い、
 * パレット外（ユーザーが将来別経路で入れた色・フォールバック色）は
 * WCAG 2.1 のコントラスト比が高い方を白／黒から選ぶ。
 */
function textColorOn(background: string): string {
  const entry = CALENDAR_LAYER_PALETTE.find(p => p.hex.toUpperCase() === background.toUpperCase())
  if (entry) return entry.lightText
  return contrastRatio(background, '#FFFFFF') >= contrastRatio(background, '#000000')
    ? '#FFFFFF'
    : '#000000'
}

function chipStyle(option: ScopeOption): Record<string, string> {
  const color = option.color ?? '#94A3B8'
  if (isSelected(option.value)) {
    return { backgroundColor: color, borderColor: color, color: textColorOn(color) }
  }
  return { borderColor: color, color: 'inherit', backgroundColor: 'transparent' }
}

function onChipClick(option: ScopeOption) {
  if (openColorFor.value === option.value) return
  emit('toggle', option.value)
}

function openColorPopover(option: ScopeOption) {
  if (!canEditColor(option)) return
  openColorFor.value = option.value
}

function onContextMenu(option: ScopeOption, event: MouseEvent) {
  if (!canEditColor(option)) return
  event.preventDefault()
  openColorPopover(option)
}

function onPointerDown(option: ScopeOption) {
  if (!canEditColor(option)) return
  cancelLongPress()
  longPressTimer = setTimeout(() => openColorPopover(option), LONG_PRESS_MS)
}

function cancelLongPress() {
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

onBeforeUnmount(cancelLongPress)

function closePopover() {
  openColorFor.value = null
}

function onPickColor(option: ScopeOption, color: string) {
  if (option.layerScopeId === undefined) return
  emit('color', { scopeType: option.scopeType, scopeId: option.layerScopeId, color })
  closePopover()
}

function onResetColor(option: ScopeOption) {
  if (option.layerScopeId === undefined) return
  emit('reset-color', { scopeType: option.scopeType, scopeId: option.layerScopeId })
  closePopover()
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2" data-testid="calendar-layer-chips">
    <span class="text-xs text-surface-400">{{ t('schedule.calendar.layer.title') }}</span>

    <!-- FILTER_OVERFLOW 件超: MultiSelect へ畳む。件数は必ず見せる（存在を隠さない） -->
    <template v-if="isCollapsed">
      <span class="text-xs text-surface-500" data-testid="layer-count">
        {{ t('schedule.calendar.layer.count', { count: options.length }) }}
      </span>
      <MultiSelect
        v-model="multiSelectValue"
        :options="options"
        option-label="label"
        option-value="value"
        :max-selected-labels="2"
        class="text-xs"
        style="min-width: 180px"
      />
    </template>

    <!-- FILTER_OVERFLOW 件以下: 色ドット＋名前のチップ列 -->
    <template v-else>
      <div
        v-for="sc in options"
        :key="sc.value"
        class="relative inline-flex"
      >
        <button
          type="button"
          class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs transition-colors"
          :style="chipStyle(sc)"
          :aria-pressed="isSelected(sc.value)"
          :data-testid="`layer-chip-${sc.value}`"
          @click="onChipClick(sc)"
          @contextmenu="onContextMenu(sc, $event)"
          @pointerdown="onPointerDown(sc)"
          @pointerup="cancelLongPress"
          @pointerleave="cancelLongPress"
        >
          <!-- 色ドット（色だけに識別を担わせないため、必ず名前と併記する） -->
          <span
            class="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
            :style="{ backgroundColor: sc.color ?? '#94A3B8' }"
            aria-hidden="true"
            data-testid="layer-chip-dot"
          />
          <span>{{ sc.label }}</span>
        </button>

        <!-- モバイル向けの色変更入口（長押し／右クリックと同じポップオーバーを開く） -->
        <button
          v-if="canEditColor(sc)"
          type="button"
          class="ml-0.5 rounded-full px-1 text-xs text-surface-400 hover:text-surface-600"
          :aria-label="t('schedule.calendar.layer.color')"
          :data-testid="`layer-chip-more-${sc.value}`"
          @click="openColorPopover(sc)"
        >
          …
        </button>

        <!-- 色変更ポップオーバー（§3.3 のパレット12色＋自動に戻す） -->
        <div
          v-if="openColorFor === sc.value"
          class="absolute left-0 top-full z-20 mt-1 w-44 rounded-md border border-surface-200 bg-surface-0 p-2 shadow-lg dark:border-surface-700 dark:bg-surface-900"
          :data-testid="`layer-color-popover-${sc.value}`"
        >
          <p class="mb-1 text-xs text-surface-500">
            {{ t('schedule.calendar.layer.color') }}
          </p>
          <div class="flex flex-wrap gap-1">
            <button
              v-for="p in CALENDAR_LAYER_PALETTE"
              :key="p.hex"
              type="button"
              class="h-5 w-5 rounded-full border border-surface-200"
              :style="{ backgroundColor: p.hex }"
              :aria-label="p.hex"
              :data-testid="`layer-color-${p.hex}`"
              @click="onPickColor(sc, p.hex)"
            />
          </div>
          <button
            type="button"
            class="mt-2 w-full rounded border border-surface-300 px-2 py-1 text-xs"
            :data-testid="`layer-color-auto-${sc.value}`"
            @click="onResetColor(sc)"
          >
            {{ t('schedule.calendar.layer.colorAuto') }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
