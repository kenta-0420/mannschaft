<script setup lang="ts">
import type { RecruitmentDistributionTargetType, RecruitmentVisibility } from '~/types/recruitment'

const props = defineProps<{
  modelValue: RecruitmentDistributionTargetType[]
  visibility: RecruitmentVisibility
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: RecruitmentDistributionTargetType[]]
}>()

const { t } = useI18n()

const ALL_TYPES: RecruitmentDistributionTargetType[] = ['MEMBERS', 'SUPPORTERS', 'FOLLOWERS', 'PUBLIC_FEED']

function isChecked(type: RecruitmentDistributionTargetType): boolean {
  return props.modelValue.includes(type)
}

function toggle(type: RecruitmentDistributionTargetType) {
  const next = isChecked(type)
    ? props.modelValue.filter((t) => t !== type)
    : [...props.modelValue, type]
  emit('update:modelValue', next)
}

/**
 * visibility との整合性警告を返す。
 * - PUBLIC なのに PUBLIC_FEED が含まれない → 警告
 * - SUPPORTERS_ONLY なのに SUPPORTERS が含まれない → 警告
 */
const warnings = computed<string[]>(() => {
  const msgs: string[] = []
  if (props.visibility === 'PUBLIC' && !isChecked('PUBLIC_FEED')) {
    msgs.push(t('recruitment.distribution.warnPublicNeedsFeed'))
  }
  if (props.visibility === 'SUPPORTERS_ONLY' && !isChecked('SUPPORTERS')) {
    msgs.push(t('recruitment.distribution.warnSupportersOnlyNeedsSupporter'))
  }
  return msgs
})

const typeLabels: Record<RecruitmentDistributionTargetType, string> = {
  MEMBERS: t('recruitment.distribution.members'),
  SUPPORTERS: t('recruitment.distribution.supporters'),
  FOLLOWERS: t('recruitment.distribution.followers'),
  PUBLIC_FEED: t('recruitment.distribution.publicFeed'),
}

const typeDescriptions: Record<RecruitmentDistributionTargetType, string> = {
  MEMBERS: t('recruitment.distribution.membersDesc'),
  SUPPORTERS: t('recruitment.distribution.supportersDesc'),
  FOLLOWERS: t('recruitment.distribution.followersDesc'),
  PUBLIC_FEED: t('recruitment.distribution.publicFeedDesc'),
}
</script>

<template>
  <div class="space-y-3">
    <label class="block text-sm font-medium text-surface-700 dark:text-surface-300">
      {{ $t('recruitment.distribution.title') }}
    </label>

    <div class="space-y-2">
      <div
        v-for="type in ALL_TYPES"
        :key="type"
        class="flex items-start gap-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
        :class="{ 'opacity-60': disabled }"
      >
        <Checkbox
          :model-value="isChecked(type)"
          :disabled="disabled"
          binary
          @update:model-value="toggle(type)"
        />
        <div>
          <p class="text-sm font-medium">
            {{ typeLabels[type] }}
          </p>
          <p class="text-xs text-surface-500">
            {{ typeDescriptions[type] }}
          </p>
        </div>
      </div>
    </div>

    <!-- 整合性警告 -->
    <div v-if="warnings.length > 0" class="space-y-1">
      <div
        v-for="warn in warnings"
        :key="warn"
        class="flex items-center gap-2 rounded bg-yellow-50 p-2 text-xs text-yellow-700 dark:bg-yellow-900/20 dark:text-yellow-400"
      >
        <i class="pi pi-exclamation-triangle" />
        {{ warn }}
      </div>
    </div>
  </div>
</template>
