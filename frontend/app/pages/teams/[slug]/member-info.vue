<script setup lang="ts">
import type { MemberInfoResponseMeItem } from '~/types/memberInfo'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
const notification = useNotification()
const memberInfoApi = useMemberInfoApi()
const { formatDate } = useDatetime()

const items = ref<MemberInfoResponseMeItem[]>([])
const loading = ref(false)
const saving = ref(false)
const editValues = ref<Record<number, string | null>>({})

async function loadResponses() {
  loading.value = true
  try {
    const res = await memberInfoApi.getMyResponses(teamSlug)
    items.value = res.data
    for (const item of res.data) {
      editValues.value[item.fieldId] = item.value
    }
  } catch {
    notification.error(t('common.error.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function saveAll() {
  saving.value = true
  try {
    await memberInfoApi.upsertMyResponses(teamSlug, {
      responses: items.value.map((item) => ({
        fieldId: item.fieldId,
        value: editValues.value[item.fieldId] ?? null,
      })),
    })
    notification.success(t('memberInfo.response.saved'))
    await loadResponses()
  } catch {
    notification.error(t('common.error.saveFailed'))
  } finally {
    saving.value = false
  }
}

onMounted(loadResponses)
</script>

<template>
  <div class="container mx-auto max-w-2xl p-4">
    <PageHeader :title="$t('memberInfo.response.title')" class="mb-4" />

    <div v-if="loading" class="flex flex-col gap-4">
      <Skeleton height="6rem" class="rounded-lg" />
      <Skeleton height="6rem" class="rounded-lg" />
      <Skeleton height="6rem" class="rounded-lg" />
    </div>

    <DashboardEmptyState
      v-else-if="items.length === 0"
      icon="pi pi-inbox"
      :message="$t('memberInfo.response.myInfo')"
    />

    <div v-else class="flex flex-col gap-4">
      <div
        v-for="item in items"
        :key="item.fieldId"
        class="rounded-lg border p-4 transition-colors"
        :class="item.isOverdue
          ? 'border-red-400 bg-red-50 dark:bg-red-950'
          : 'border-surface-200 bg-white dark:border-surface-700 dark:bg-surface-800'"
      >
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <span class="font-medium text-surface-700 dark:text-surface-200">
            {{ item.fieldName }}
          </span>
          <Tag v-if="item.isRequired" :value="$t('memberInfo.field.required')" severity="danger" class="text-xs" />
          <Tag v-if="item.isOverdue" :value="$t('memberInfo.response.overdue')" severity="danger" />
        </div>

        <div class="mb-3">
          <InputText
            v-if="item.fieldType === 'TEXT'"
            v-model="editValues[item.fieldId]"
            class="w-full"
          />
          <InputText
            v-else-if="item.fieldType === 'PHONE'"
            v-model="editValues[item.fieldId]"
            type="tel"
            inputmode="tel"
            class="w-full"
          />
          <InputText
            v-else-if="item.fieldType === 'EMAIL'"
            v-model="editValues[item.fieldId]"
            type="email"
            class="w-full"
          />
          <input
            v-else-if="item.fieldType === 'DATE'"
            v-model="editValues[item.fieldId]"
            type="date"
            class="w-full rounded-md border border-surface-300 bg-white px-3 py-2 text-sm text-surface-700 focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-200"
          />
        </div>

        <div class="flex flex-wrap gap-4 text-xs text-surface-400 dark:text-surface-500">
          <span>
            {{ $t('memberInfo.response.confirmedAt') }}:
            {{ item.confirmedAt ? formatDate(item.confirmedAt) : $t('memberInfo.response.notAnswered') }}
          </span>
          <span v-if="item.nextDueAt">
            {{ $t('memberInfo.response.nextDue') }}:
            {{ formatDate(item.nextDueAt) }}
          </span>
        </div>
      </div>

      <div class="mt-2 flex justify-end">
        <Button
          :label="$t('memberInfo.response.saveAll')"
          icon="pi pi-save"
          :loading="saving"
          @click="saveAll"
        />
      </div>
    </div>
  </div>
</template>
