<script setup lang="ts">
import type { BulletinThreadResponse } from '~/types/bulletin'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

const selectedThread = ref<BulletinThreadResponse | null>(null)
const showCreateDialog = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

function onSaved() {
  listRef.value?.refresh()
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="掲示板" />
    </div>

    <div v-if="selectedThread" class="mx-auto max-w-3xl">
      <BulletinThreadDetail
        :thread-id="selectedThread.id"
        :can-manage="isAdminOrDeputy"
        @back="selectedThread = null"
      />
    </div>

    <div v-else>
      <BulletinThreadList
        ref="listRef"
        scope-type="TEAM"
        :scope-id="teamId"
        :can-manage="isAdminOrDeputy"
        @select="(t) => selectedThread = t"
        @create="showCreateDialog = true"
      />
    </div>

    <BulletinThreadForm
      v-model:visible="showCreateDialog"
      scope-type="TEAM"
      :scope-id="teamId"
      @saved="onSaved"
    />
  </div>
</template>
