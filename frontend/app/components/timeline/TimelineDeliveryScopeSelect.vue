<script setup lang="ts">
/**
 * 組織タイムラインの「配信範囲」選択（CMP-058）。
 *
 * <p>3択（`DIRECT` / `CHILDREN` / `DESCENDANTS`）のラジオ選択。既定は `DIRECT`
 * （この団体のメンバーだけ）で、**何も触らなければ既定のまま送信される**。
 * ADHD 傾向のユーザー向けに、既定以外を選ぶときだけ意識すればよい段階開示にしている
 * （既定以外を選んだときのみ補足文を出す）。</p>
 *
 * <p>表示できるのは組織の ADMIN / DEPUTY_ADMIN のときだけ。権限が無い人に見せると
 * BE が `COMMON_002`(403) で必ず弾くため、意味の分からない失敗体験になる
 * （可視制御は呼び出し元 {@link TimelinePostForm} が行う）。</p>
 */
import type { TimelineDeliveryScope } from '~/types/timeline'
import { DELIVERY_SCOPE_OPTIONS, DEFAULT_DELIVERY_SCOPE } from '~/types/timeline'

const props = defineProps<{
  modelValue: TimelineDeliveryScope
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: TimelineDeliveryScope]
}>()

const { t } = useI18n()

const options = DELIVERY_SCOPE_OPTIONS

/** 既定（DIRECT）以外を選んだときだけ補足を出す（段階開示）。 */
const showHint = computed(() => props.modelValue !== DEFAULT_DELIVERY_SCOPE)

function select(value: TimelineDeliveryScope) {
  if (props.disabled) return
  emit('update:modelValue', value)
}
</script>

<template>
  <fieldset class="flex flex-col gap-1" data-testid="timeline-delivery-scope">
    <legend class="mb-1 text-sm text-surface-700 dark:text-surface-200">
      {{ t('timeline.deliveryScope.label') }}
    </legend>
    <div class="flex flex-col gap-1">
      <div
        v-for="option in options"
        :key="option"
        class="flex items-center gap-2"
      >
        <RadioButton
          :model-value="modelValue"
          :value="option"
          :disabled="disabled"
          :input-id="`deliveryScope-${option}`"
          name="deliveryScope"
          :data-testid="`timeline-delivery-scope-${option}`"
          @update:model-value="select(option)"
        />
        <label
          :for="`deliveryScope-${option}`"
          class="cursor-pointer select-none text-sm"
          :class="disabled ? 'text-surface-400' : 'text-surface-700 dark:text-surface-200'"
        >
          {{ t(`timeline.deliveryScope.options.${option}`) }}
        </label>
      </div>
    </div>
    <p v-if="showHint" class="ml-6 text-xs text-surface-400" data-testid="timeline-delivery-scope-hint">
      {{ t(`timeline.deliveryScope.hints.${modelValue}`) }}
    </p>
  </fieldset>
</template>
