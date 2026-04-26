<script setup lang="ts">
import type { SurveyResponse } from '~/types/survey'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)

const { t } = useI18n()

const surveyListRef = ref<{ refresh: () => Promise<void> } | null>(null)
const showCreateDialog = ref(false)

function onSelect(survey: SurveyResponse) {
  navigateTo({
    path: `/surveys/${survey.id}`,
    query: { scope: 'team', scopeId: String(teamId) },
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
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader :title="t('surveys.pageTitle')" />
    </div>

    <SurveyList
      ref="surveyListRef"
      scope-type="TEAM"
      :scope-id="teamId"
      @select="onSelect"
      @create="onCreate"
    />

    <SurveyCreateDialog
      v-model:visible="showCreateDialog"
      scope-type="TEAM"
      :scope-id="teamId"
      @created="onCreated"
    />
  </div>
</template>
