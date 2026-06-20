<script setup lang="ts">
import type { SurveyResponse } from '~/types/survey'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)

const { t } = useI18n()

const surveyListRef = ref<{ refresh: () => Promise<void> } | null>(null)
const showCreateDialog = ref(false)

function onSelect(survey: SurveyResponse) {
  navigateTo({
    path: `/surveys/${survey.id}`,
    query: { scope: 'organization', scopeId: String(orgSlug) },
  })
}

function onCreate() {
  showCreateDialog.value = true
}

async function onCreated(_survey: SurveyResponse) {
  // 作成完了 → 一覧をリフレッシュ
  await surveyListRef.value?.refresh()
}
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader :title="t('surveys.pageTitle')" />
    </div>

    <SurveyList
      ref="surveyListRef"
      scope-type="ORGANIZATION"
      :scope-id="orgSlug"
      @select="onSelect"
      @create="onCreate"
    />

    <SurveyCreateDialog
      v-model:visible="showCreateDialog"
      scope-type="ORGANIZATION"
      :scope-id="orgSlug"
      @created="onCreated"
    />
  </div>
</template>
