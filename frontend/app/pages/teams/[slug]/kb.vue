<script setup lang="ts">
import type { KbPageSummaryResponse, KbPageResponse } from '~/types/knowledgeBase'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const selectedPageId = ref<number | null>(null)
const showFormDialog = ref(false)
const editPage = ref<KbPageResponse | null>(null)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSelectPage(page: KbPageSummaryResponse) {
  selectedPageId.value = page.id
}

function onCreatePage() {
  editPage.value = null
  showFormDialog.value = true
}

function onEditPage(page: KbPageResponse) {
  editPage.value = page
  showFormDialog.value = true
}

function onSaved() {
  listRef.value?.refresh()
  if (selectedPageId.value) {
    // Force detail reload by resetting and re-setting
    const id = selectedPageId.value
    selectedPageId.value = null
    nextTick(() => { selectedPageId.value = id })
  }
}

function onDeleted() {
  selectedPageId.value = null
  listRef.value?.refresh()
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="ナレッジベース" />
    </div>

    <div v-if="selectedPageId" class="mx-auto max-w-3xl">
      <KbPageDetail
        scope-type="teams"
        :scope-id="teamSlug"
        :page-id="selectedPageId"
        :can-manage="isAdminOrDeputy"
        @back="selectedPageId = null"
        @edit="onEditPage"
        @deleted="onDeleted"
        @updated="onSaved"
      />
    </div>

    <div v-else>
      <KbPageTree
        ref="listRef"
        scope-type="teams"
        :scope-id="teamSlug"
        :can-manage="isAdminOrDeputy"
        @select="onSelectPage"
        @create="onCreatePage"
      />
    </div>

    <KbPageForm
      v-model:visible="showFormDialog"
      scope-type="teams"
      :scope-id="teamSlug"
      :edit-page="editPage"
      @saved="onSaved"
    />
  </div>
</template>
