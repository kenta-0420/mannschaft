<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

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
        <NuxtLink :to="`/teams/${teamId}/forms`" class="text-sm text-primary hover:underline">
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
      scope-type="team"
      :scope-id="teamId"
      :can-edit="isAdminOrDeputy"
    />

    <FormTemplateEditor
      v-model:visible="showCreateDialog"
      scope-type="team"
      :scope-id="teamId"
      @saved="onSaved"
    />
  </div>
</template>
