<script setup lang="ts">
import { computed } from 'vue'

/**
 * F03.12 §14 主催者点呼の過去セッション履歴ドロワー。
 *
 * <p>Phase 10 では BE が点呼セッション ID 一覧（{@code string[]}）のみを返すため、
 * UUID をそのまま並べる最小実装に留める。詳細表示は Phase 11 送り。</p>
 */

const props = defineProps<{
  /** v-model:visible 用。 */
  visible: boolean
  /** 過去の点呼セッション UUID 一覧。 */
  sessionIds: string[]
  /** 読み込み中表示。 */
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
}>()

const isVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})
</script>

<template>
  <Drawer
    v-model:visible="isVisible"
    position="right"
    :header="$t('event.rollCall.historyTitle')"
    :style="{ width: 'min(420px, 92vw)' }"
    data-testid="roll-call-history-drawer"
  >
    <div v-if="loading" class="rc-history__loading">
      {{ $t('common.loading') }}
    </div>
    <ul v-else-if="sessionIds.length > 0" class="rc-history__list">
      <li
        v-for="(id, idx) in sessionIds"
        :key="id"
        class="rc-history__item"
        :data-testid="`roll-call-history-item-${idx}`"
      >
        <span class="rc-history__index">#{{ sessionIds.length - idx }}</span>
        <code class="rc-history__uuid">{{ id }}</code>
      </li>
    </ul>
    <p v-else class="rc-history__empty" data-testid="roll-call-history-empty">
      {{ $t('event.rollCall.historyEmpty') }}
    </p>
  </Drawer>
</template>

<style scoped>
.rc-history__list {
  list-style: none;
  padding: 0;
  margin: 0;
}
.rc-history__item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
}
.rc-history__index {
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
}
.rc-history__uuid {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.78rem;
  word-break: break-all;
}
.rc-history__empty,
.rc-history__loading {
  color: var(--p-text-muted-color, #6b7280);
  text-align: center;
  padding: 1.5rem 0;
}
</style>
