<script setup lang="ts">
import type { FormTemplateResponse } from '~/types/form'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = Number(route.params.id)
const { loadPermissions } = useRoleAccess('organization', orgId)

const formApi = useFormApi()

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
      <h1 class="text-2xl font-bold">フォーム</h1>
      <NuxtLink :to="`/organizations/${orgId}/forms/templates`">
        <Button label="テンプレート管理" icon="pi pi-cog" outlined />
      </NuxtLink>
    </div>

    <!-- 公開中フォーム一覧 -->
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner />
    </div>

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
            <i v-if="tpl.icon" :class="tpl.icon" :style="tpl.color ? { color: tpl.color } : {}" />
            <span>{{ tpl.name }}</span>
          </div>
        </template>
        <template #content>
          <p v-if="tpl.description" class="mb-2 text-sm text-surface-500">{{ tpl.description }}</p>
          <div class="flex items-center justify-between text-xs text-surface-400">
            <span
              >提出数: {{ tpl.submissionCount
              }}<span v-if="tpl.targetCount"> / {{ tpl.targetCount }}</span></span
            >
            <span v-if="tpl.deadline"
              >期限: {{ new Date(tpl.deadline).toLocaleDateString('ja-JP') }}</span
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
