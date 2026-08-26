<script setup lang="ts">
definePageMeta({
  layout: 'organization',
  middleware: 'auth',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

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
      <PageHeader title="フォームテンプレート" :back-to="`/organizations/${orgSlug}/forms`" back-label="フォームに戻る" />
      <Button
        v-if="isAdminOrDeputy"
        label="テンプレート作成"
        icon="pi pi-plus"
        @click="showCreateDialog = true"
      />
    </div>

    <FormTemplateList
      ref="listRef"
      scope-type="organization"
      :scope-id="orgSlug"
      :can-edit="isAdminOrDeputy"
    />

    <FormTemplateEditor
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="orgSlug"
      @saved="onSaved"
    />
  </div>
</template>
