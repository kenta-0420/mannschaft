<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

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
      <div class="flex items-center gap-3">
        <NuxtLink
          :to="`/organizations/${orgId}/forms`"
          class="text-sm text-primary hover:underline"
        >
          <i class="pi pi-arrow-left mr-1" />フォームに戻る
        </NuxtLink>
        <h1 class="text-2xl font-bold">フォームテンプレート</h1>
      </div>
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
      :scope-id="orgId"
      :can-edit="isAdminOrDeputy"
    />

    <FormTemplateEditor
      v-model:visible="showCreateDialog"
      scope-type="organization"
      :scope-id="orgId"
      @saved="onSaved"
    />
  </div>
</template>
