<script setup lang="ts">
definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamSlug)

const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSaved() {
  listRef.value?.refresh()
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="フォームテンプレート" :back-to="`/teams/${teamSlug}/forms`" back-label="フォームに戻る" />
      <Button
        v-if="isAdminOrDeputy"
        label="テンプレート作成"
        icon="pi pi-plus"
        @click="showCreateDialog = true"
      />
    </div>

    <FormTemplateList
      ref="listRef"
      scope-type="team"
      :scope-id="teamSlug"
      :can-edit="isAdminOrDeputy"
    />

    <FormTemplateEditor
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="teamSlug"
      @saved="onSaved"
    />
  </div>
</template>
