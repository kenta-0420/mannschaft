<script setup lang="ts">
import type { MemberInfoResponseMeItem } from '~/types/memberInfo'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
}>()

const { t } = useI18n()
const memberInfoApi = useMemberInfoApi()
const { captureQuiet } = useErrorReport()

const items = ref<MemberInfoResponseMeItem[]>([])
const loading = ref(true)

const overdueCount = computed(() => items.value.filter((i) => i.isOverdue).length)
const unansweredCount = computed(() => items.value.filter((i) => i.value === null).length)
const hasIssue = computed(() => overdueCount.value > 0 || unansweredCount.value > 0)

async function load() {
  loading.value = true
  try {
    const res = await memberInfoApi.getMyResponses(props.scopeId)
    items.value = res.data
  } catch (e) {
    captureQuiet(e, { context: 'WidgetMemberInfo: メンバー情報取得' })
    items.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div v-if="loading" class="space-y-2">
      <Skeleton height="1.5rem" />
      <Skeleton height="1.5rem" width="70%" />
    </div>

    <div v-else-if="items.length === 0" class="py-4 text-center text-sm text-surface-400">
      {{ t('memberInfo.settings.fieldList') }}のフィールドがまだ設定されていません
    </div>

    <div v-else class="space-y-2">
      <div
        v-if="hasIssue"
        class="flex items-center gap-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 dark:border-red-800 dark:bg-red-950"
      >
        <i class="pi pi-exclamation-circle text-red-500" />
        <div class="flex-1 text-sm">
          <span v-if="overdueCount > 0" class="text-red-600">
            {{ t('memberInfo.response.overdue') }}: {{ overdueCount }}件
          </span>
          <span v-if="overdueCount > 0 && unansweredCount > 0" class="mx-1 text-surface-400">・</span>
          <span v-if="unansweredCount > 0" class="text-surface-500">
            {{ t('memberInfo.response.notAnswered') }}: {{ unansweredCount }}件
          </span>
        </div>
      </div>

      <div
        v-else
        class="flex items-center gap-3 rounded-lg border border-green-200 bg-green-50 px-3 py-2 dark:border-green-800 dark:bg-green-950"
      >
        <i class="pi pi-check-circle text-green-500" />
        <span class="text-sm text-green-600">{{ t('memberInfo.status.completed') }}</span>
      </div>

      <div class="text-xs text-surface-400">
        全{{ items.length }}フィールド
      </div>
    </div>
  </div>
</template>
