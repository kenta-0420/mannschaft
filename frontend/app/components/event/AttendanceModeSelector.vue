<script setup lang="ts">
import type { AttendanceMode } from '~/types/event'

const props = defineProps<{
  modelValue: AttendanceMode
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: AttendanceMode]
}>()

const { t } = useI18n()

const descriptions: Record<AttendanceMode, string> = {
  NONE: t('event.attendanceMode.noneDesc', '参加登録なしで誰でも参加できます'),
  RSVP: t('event.attendanceMode.rsvpDesc', '参加予定・不参加などの出欠確認を行います'),
  REGISTRATION: t('event.attendanceMode.registrationDesc', '参加には事前登録が必要です'),
}

const options: { label: string; value: AttendanceMode; icon: string; description: string }[] = [
  {
    label: t('event.attendanceMode.none'),
    value: 'NONE',
    icon: 'pi pi-users',
    description: descriptions.NONE,
  },
  {
    label: t('event.attendanceMode.rsvp'),
    value: 'RSVP',
    icon: 'pi pi-check-circle',
    description: descriptions.RSVP,
  },
  {
    label: t('event.attendanceMode.registration'),
    value: 'REGISTRATION',
    icon: 'pi pi-user-plus',
    description: descriptions.REGISTRATION,
  },
]

function select(value: AttendanceMode) {
  if (!props.disabled) {
    emit('update:modelValue', value)
  }
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <label class="mb-1 block text-sm font-medium">{{ $t('event.attendanceMode.label') }}</label>
    <div class="flex flex-col gap-2 sm:flex-row">
      <button
        v-for="opt in options"
        :key="opt.value"
        type="button"
        class="flex flex-1 cursor-pointer items-center gap-3 rounded-lg border p-3 text-left transition-colors"
        :class="[
          modelValue === opt.value
            ? 'border-primary bg-primary/5 text-primary dark:bg-primary/10'
            : 'border-surface-300 hover:border-primary/50 dark:border-surface-600',
          disabled ? 'cursor-not-allowed opacity-60' : '',
        ]"
        :aria-checked="modelValue === opt.value"
        role="radio"
        :disabled="disabled"
        @click="select(opt.value)"
      >
        <i
          :class="[
            opt.icon,
            'text-xl',
            modelValue === opt.value ? 'text-primary' : 'text-surface-500',
          ]"
        />
        <div>
          <p class="text-sm font-medium">{{ opt.label }}</p>
          <p class="text-xs text-surface-500">{{ opt.description }}</p>
        </div>
      </button>
    </div>
  </div>
</template>
