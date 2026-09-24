<script setup lang="ts">
import type { FormTemplateResponse } from '~/types/form'

definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { loadPermissions } = useRoleAccess('team', teamSlug)

const formApi = useFormApi()
const { formatDate } = useDatetime()

const templates = ref<FormTemplateResponse[]>([])
const loading = ref(true)
const showSubmitDialog = ref(false)
const selectedTemplateId = ref<number | null>(null)
const submissionListRef = ref<{ refresh: () => void } | null>(null)
/** 取得失敗は「フォームなし」ではない。空状態へフォールバックせずエラー状態を出す。 */
const loadFailed = ref(false)

async function loadPublishedTemplates() {
  loading.value = true
  loadFailed.value = false
  try {
    const res = await formApi.listTemplates('team', teamSlug, { status: 'PUBLISHED', size: 100 })
    templates.value = res.data
  } catch {
    templates.value = []
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

function openSubmit(templateId: number) {
  selectedTemplateId.value = templateId
  showSubmitDialog.value = true
}

function onSaved() {
  submissionListRef.value?.refresh()
}

onMounted(async () => {
  await loadPermissions()
  await loadPublishedTemplates()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="フォーム" />
      <NuxtLink :to="`/teams/${teamSlug}/forms/templates`">
        <Button label="テンプレート管理" icon="pi pi-cog" outlined />
      </NuxtLink>
    </div>

    <!-- 公開中フォーム一覧 -->
    <PageLoading v-if="loading" size="40px" />

    <div
      v-else-if="templates.length > 0"
      class="mb-6 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3"
    >
      <Card
        v-for="tpl in templates"
        :key="tpl.id"
        class="cursor-pointer hover:shadow-lg"
        @click="openSubmit(tpl.id)"
      >
        <template #title>
          <div class="flex items-center gap-2">
            <i v-if="tpl.content?.icon" :class="tpl.content.icon" :style="tpl.content?.color ? { color: tpl.content.color } : {}" />
            <span>{{ tpl.content?.name }}</span>
          </div>
        </template>
        <template #content>
          <p v-if="tpl.content?.description" class="mb-2 text-sm text-surface-500">{{ tpl.content.description }}</p>
          <div class="flex items-center justify-between text-xs text-surface-400">
            <span
              >提出数: {{ tpl.stats?.submissionCount
              }}<span v-if="tpl.stats?.targetCount"> / {{ tpl.stats.targetCount }}</span></span
            >
            <span v-if="tpl.timeline?.deadline"
              >期限: {{ formatDate(tpl.timeline.deadline) }}</span
            >
          </div>
        </template>
      </Card>
    </div>

    <DashboardErrorState
      v-else-if="loadFailed"
      testid="forms-list-error-state"
      @retry="loadPublishedTemplates"
    />

    <DashboardEmptyState v-else icon="pi pi-file-edit" message="公開中のフォームはありません" />

    <!-- 自分の提出一覧 -->
    <h2 class="mb-3 text-lg font-bold">自分の提出</h2>
    <FormSubmissionList
      ref="submissionListRef"
      scope-type="team"
      :scope-id="teamSlug"
      :my-only="true"
    />

    <!-- 回答ダイアログ -->
    <FormSubmissionForm
      v-if="selectedTemplateId"
      v-model:visible="showSubmitDialog"
      scope-type="team"
      :scope-id="teamSlug"
      :template-id="selectedTemplateId"
      @saved="onSaved"
    />
  </div>
</template>
