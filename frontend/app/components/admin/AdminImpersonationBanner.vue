<script setup lang="ts">
import Button from 'primevue/button'

const store = useAdminImpersonationStore()
const router = useRouter()
const { t } = useI18n()

function exitImpersonation() {
  store.stopImpersonation()
  router.push('/system-admin/feedbacks')
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="store.isImpersonating"
      class="fixed top-0 left-0 right-0 z-[9999] bg-orange-500 text-white py-2 px-4 flex items-center justify-between shadow-md"
    >
      <div class="flex items-center gap-2">
        <i class="pi pi-user-edit text-white" />
        <span class="text-sm font-semibold">
          {{ t('admin.impersonation.banner', { label: store.targetUserLabel }) }}
        </span>
      </div>
      <Button
        :label="t('admin.impersonation.exit')"
        icon="pi pi-times"
        text
        size="small"
        class="text-white hover:text-orange-100"
        @click="exitImpersonation"
      />
    </div>
  </Teleport>
</template>
