<script setup lang="ts">
import type { MemberResponse } from '~/types/member'
import type { ScheduleTargetMode } from '~/types/schedule'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  targetMode: ScheduleTargetMode
  targetUserIds: number[]
}>()

const emit = defineEmits<{
  'update:targetMode': [value: ScheduleTargetMode]
  'update:targetUserIds': [value: number[]]
  invalid: [message: string | null]
}>()

const api = useApi()
const { t } = useI18n()
const members = ref<MemberResponse[]>([])
const loading = ref(false)

const mode = computed({
  get: () => props.targetMode,
  set: (value: ScheduleTargetMode) => emit('update:targetMode', value),
})
const selectedIds = computed({
  get: () => props.targetUserIds,
  set: (value: number[]) => emit('update:targetUserIds', [...new Set(value)]),
})
const base = computed(() => props.scopeType === 'team' ? 'teams' : 'organizations')

async function loadMembers() {
  loading.value = true
  try {
    const response = await api<{ data: MemberResponse[] }>(
      `/api/v1/${base.value}/${props.scopeId}/members?page=0&size=500`,
    )
    members.value = response.data ?? []
  } finally {
    loading.value = false
  }
}

watch(mode, (value) => {
  if (value === 'ALL_MEMBERS') emit('update:targetUserIds', [])
})

watch(selectedIds, (ids) => {
  const message = mode.value === 'SELECTED_MEMBERS' && (ids.length < 1 || ids.length > 500)
    ? t('schedule.targetAudience.invalidSelection')
    : null
  emit('invalid', message)
}, { immediate: true })

onMounted(loadMembers)
</script>

<template>
  <fieldset class="flex flex-col gap-2 rounded-lg border border-surface-200 p-3 dark:border-surface-700">
    <legend class="px-1 text-sm font-medium">{{ $t('schedule.targetAudience.label') }}</legend>
    <div class="flex flex-wrap gap-3">
      <div class="flex items-center gap-2">
        <RadioButton v-model="mode" input-id="scheduleTargetAll" value="ALL_MEMBERS" />
        <label for="scheduleTargetAll" class="text-sm">{{ $t('schedule.targetAudience.allMembers') }}</label>
      </div>
      <div class="flex items-center gap-2">
        <RadioButton v-model="mode" input-id="scheduleTargetSelected" value="SELECTED_MEMBERS" />
        <label for="scheduleTargetSelected" class="text-sm">{{ $t('schedule.targetAudience.selectMembers') }}</label>
      </div>
    </div>
    <MultiSelect
      v-if="mode === 'SELECTED_MEMBERS'"
      v-model="selectedIds"
      :options="members"
      option-label="displayName"
      option-value="userId"
      :loading="loading"
      :max-selected-labels="3"
      :placeholder="$t('schedule.targetAudience.memberPlaceholder')"
      class="w-full"
      :aria-label="$t('schedule.targetAudience.memberPlaceholder')"
    >
      <template #option="{ option }">
        <div class="flex min-w-0 items-center gap-2">
          <span class="h-3 w-3 shrink-0 rounded-full" :style="{ backgroundColor: option.calendarColor ?? '#64748b' }" aria-hidden="true" />
          <Avatar :image="option.avatarUrl" :label="option.avatarUrl ? undefined : option.displayName?.charAt(0)" shape="circle" size="small" />
          <span class="truncate">{{ option.displayName }}</span>
        </div>
      </template>
    </MultiSelect>
  </fieldset>
</template>
