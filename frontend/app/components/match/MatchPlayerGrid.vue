<script setup lang="ts">
// F08.10 選手グリッド（04_frontend_and_ux.md §G.13・sports/01_soccer.md §8.5）。
// 44px(2.75rem) 大タップターゲット・先発上段/控え下段・GK/DF/MF/FW 順・全員先発ワンタップ・手入力追加。
// 2 用途: (1) 選手「選択」(selectMode=true・タップで emit select)、(2) 先発「設定」(starter トグル)。
import type { GridPlayer } from '~/composables/match/useMatchPlayerGrid'

const props = defineProps<{
  players: GridPlayer[]
  /** 選択モード（true=タップで select emit / false=スタメン設定モード） */
  selectMode?: boolean
  /** 設定モード時の全員先発/手入力等のツールを表示するか */
  showTools?: boolean
}>()

const emit = defineEmits<{
  select: [player: GridPlayer]
  'toggle-starter': [index: number]
  'all-starters': []
  'clear-starters': []
  'copy-previous': []
  'add-manual': [payload: { name: string; jerseyNumber: number | null }]
}>()

const { t } = useI18n()

const manualName = ref('')
const manualNumber = ref<number | null>(null)

// 選択モードでは全選手をフラットに、設定モードでは先発/控えで分ける。
const starterPlayers = computed(() => props.players.filter((p) => p.starter))
const benchPlayers = computed(() => props.players.filter((p) => !p.starter))

function indexOf(player: GridPlayer): number {
  return props.players.indexOf(player)
}

function onTap(player: GridPlayer): void {
  if (props.selectMode) {
    emit('select', player)
  } else {
    emit('toggle-starter', indexOf(player))
  }
}

function submitManual(): void {
  const name = manualName.value.trim()
  if (!name) return
  emit('add-manual', { name, jerseyNumber: manualNumber.value })
  manualName.value = ''
  manualNumber.value = null
}

function playerKey(p: GridPlayer): string {
  return `${p.userId ?? 'm'}-${p.name}-${p.jerseyNumber ?? ''}`
}
</script>

<template>
  <div>
    <!-- ツールバー（設定モードのみ） -->
    <div v-if="!selectMode && showTools" class="mb-3 flex flex-wrap gap-2">
      <Button
        size="small"
        :label="t('match.live.grid.all_starters')"
        icon="pi pi-users"
        @click="emit('all-starters')"
      />
      <Button
        size="small"
        severity="secondary"
        text
        :label="t('match.live.grid.clear_starters')"
        @click="emit('clear-starters')"
      />
      <Button
        size="small"
        severity="secondary"
        outlined
        :label="t('match.live.grid.copy_previous')"
        icon="pi pi-copy"
        @click="emit('copy-previous')"
      />
    </div>

    <!-- 選択モード: フラットなグリッド -->
    <template v-if="selectMode">
      <div
        v-if="players.length > 0"
        class="grid grid-cols-3 gap-2 sm:grid-cols-4"
      >
        <button
          v-for="p in players"
          :key="playerKey(p)"
          type="button"
          class="flex min-h-[2.75rem] min-w-[2.75rem] flex-col items-center justify-center rounded-lg border border-surface-300 px-2 py-2 text-sm transition hover:border-primary-400"
          @click="onTap(p)"
        >
          <span v-if="p.jerseyNumber != null" class="text-xs font-bold text-primary-600">
            {{ p.jerseyNumber }}
          </span>
          <span class="truncate">{{ p.name }}</span>
        </button>
      </div>
      <p v-else class="text-sm text-surface-500">{{ t('match.live.grid.empty') }}</p>
    </template>

    <!-- 設定モード: 先発上段 / 控え下段 -->
    <template v-else>
      <div class="mb-3">
        <h4 class="mb-1 text-xs font-semibold text-surface-500">{{ t('match.live.grid.starters') }}</h4>
        <div class="grid grid-cols-3 gap-2 sm:grid-cols-4">
          <button
            v-for="p in starterPlayers"
            :key="playerKey(p)"
            type="button"
            class="flex min-h-[2.75rem] min-w-[2.75rem] flex-col items-center justify-center rounded-lg border-2 border-primary bg-primary-50 px-2 py-2 text-sm text-primary transition"
            @click="onTap(p)"
          >
            <span v-if="p.jerseyNumber != null" class="text-xs font-bold">{{ p.jerseyNumber }}</span>
            <span class="truncate">{{ p.name }}</span>
            <span v-if="p.position" class="text-[10px] text-primary-400">{{ p.position }}</span>
          </button>
        </div>
      </div>
      <div>
        <h4 class="mb-1 text-xs font-semibold text-surface-500">{{ t('match.live.grid.bench') }}</h4>
        <div class="grid grid-cols-3 gap-2 sm:grid-cols-4">
          <button
            v-for="p in benchPlayers"
            :key="playerKey(p)"
            type="button"
            class="flex min-h-[2.75rem] min-w-[2.75rem] flex-col items-center justify-center rounded-lg border border-surface-300 px-2 py-2 text-sm transition hover:border-primary-400"
            @click="onTap(p)"
          >
            <span v-if="p.jerseyNumber != null" class="text-xs font-bold text-surface-500">{{ p.jerseyNumber }}</span>
            <span class="truncate">{{ p.name }}</span>
          </button>
        </div>
      </div>
    </template>

    <!-- 手入力追加 -->
    <div v-if="showTools" class="mt-3 flex items-end gap-2">
      <div class="flex flex-1 flex-col gap-1">
        <InputText
          v-model="manualName"
          :placeholder="t('match.live.grid.manual_name_placeholder')"
          class="w-full"
        />
      </div>
      <div class="w-20">
        <InputNumber
          v-model="manualNumber"
          :placeholder="t('match.live.grid.manual_number_placeholder')"
          :min="0"
          :max="99"
          class="w-full"
        />
      </div>
      <Button
        icon="pi pi-plus"
        :aria-label="t('match.live.grid.add_manual')"
        @click="submitManual"
      />
    </div>
  </div>
</template>
