<script setup lang="ts">
import type { FormTemplateResponse } from '~/types/form'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = String(route.params.id)
const { loadPermissions } = useRoleAccess('organization', orgId)

const formApi = useFormApi()
const { formatDate } = useDatetime()

const templates = ref<FormTemplateResponse[]>([])
const loading = ref(true)
const showSubmitDialog = ref(false)
const selectedTemplateId = ref<number | null>(null)
const submissionListRef = ref<{ refresh: () => void } | null>(null)

async function loadPublishedTemplates() {
  loading.value = true
  try {
    const res = await formApi.listTemplates('organization', orgId, {
      status: 'PUBLISHED',
      size: 100,
    })
    templates.value = res.data
  } catch {
    templates.value = []
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
      <NuxtLink :to="`/organizations/${orgId}/forms/templates`">
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

    <DashboardEmptyState v-else icon="pi pi-file-edit" message="公開中のフォームはありません" />

    <!-- 自分の提出一覧 -->
    <h2 class="mb-3 text-lg font-bold">自分の提出</h2>
    <FormSubmissionList
      ref="submissionListRef"
      scope-type="organization"
      :scope-id="orgId"
      :my-only="true"
    />

    <!-- 回答ダイアログ -->
    <FormSubmissionForm
      v-if="selectedTemplateId"
      v-model:visible="showSubmitDialog"
      scope-type="organization"
      :scope-id="orgId"
      :template-id="selectedTemplateId"
      @saved="onSaved"
    />
  </div>
</template>
