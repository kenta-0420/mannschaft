<script setup lang="ts">
// F08.10 スタメン設定（04_frontend_and_ux.md §G.1c・§G.15a 前回先発コピー）。
// MatchPlayerGrid を設定モードで包み、全員先発/先発トグル/手入力/前回先発コピーのツールを提供する。
// 選手候補の保持は useMatchPlayerGrid（live.vue 側）が司り、本コンポは操作を emit するだけ。
import type { GridPlayer } from '~/composables/match/useMatchPlayerGrid'

defineProps<{
  visible: boolean
  players: GridPlayer[]
}>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
  'toggle-starter': [index: number]
  'all-starters': []
  'clear-starters': []
  'copy-previous': []
  'add-manual': [payload: { name: string; jerseyNumber: number | null }]
}>()

const { t } = useI18n()
</script>

<template>
  <Drawer
    :visible="visible"
    position="bottom"
    :header="t('match.live.grid.starters')"
    class="!h-auto max-h-[85vh]"
    @update:visible="emit('update:visible', $event)"
  >
    <MatchPlayerGrid
      :players="players"
      show-tools
      @toggle-starter="emit('toggle-starter', $event)"
      @all-starters="emit('all-starters')"
      @clear-starters="emit('clear-starters')"
      @copy-previous="emit('copy-previous')"
      @add-manual="emit('add-manual', $event)"
    />
  </Drawer>
</template>
