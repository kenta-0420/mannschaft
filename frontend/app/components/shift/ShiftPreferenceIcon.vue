<script setup lang="ts">
import type { ShiftPreference } from '~/types/shift'
import { preferenceToColor, preferenceToI18nKey } from '~/utils/shiftPreference'

const props = defineProps<{
  preference: ShiftPreference
  /** true の場合はラベルも表示する */
  showLabel?: boolean
  size?: 'sm' | 'md'
}>()

const { t } = useI18n()

const iconMap: Record<ShiftPreference, string> = {
  PREFERRED: 'pi pi-star-fill',
  AVAILABLE: 'pi pi-circle',
  WEAK_REST: 'pi pi-minus',
  STRONG_REST: 'pi pi-times',
  ABSOLUTE_REST: 'pi pi-ban',
}

const icon = computed(() => iconMap[props.preference])
const colorClass = computed(() => preferenceToColor(props.preference))
const label = computed(() => t(preferenceToI18nKey(props.preference)))
const sizeClass = computed(() => props.size === 'sm' ? 'text-xs px-1.5 py-0.5' : 'text-sm px-2 py-1')
</script>

<template>
  <span
    class="inline-flex items-center gap-1 rounded-full border font-medium"
    :class="[colorClass, sizeClass]"
    :title="label"
  >
    <i :class="icon" />
    <span v-if="showLabel">{{ label }}</span>
  </span>
</template>
