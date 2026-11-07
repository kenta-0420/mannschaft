<script setup lang="ts">
const showGuide = ref(false)
const showCreateForm = ref(false)
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})
</script>

<template>
  <div class="contact-invite-page mx-auto max-w-2xl p-6">
    <PageHeader class="contact-invite-header" :title="$t('contact_invite.page_title')" help @help="showGuide = true">
      <template #actions>
        <Button
          :label="$t('contact_invite.create_button')"
          icon="pi pi-plus"
          class="min-h-11"
          @click="showCreateForm = !showCreateForm"
        />
      </template>
    </PageHeader>
    <p class="mb-6 mt-1 text-sm text-gray-500">
      {{ $t('contact_invite.page_description') }}
    </p>
    <ContactInvitePanel v-model:show-create-form="showCreateForm" />
    <ContactInviteGuideModal v-model:visible="showGuide" />
  </div>
</template>

<style scoped>
.contact-invite-page :deep(.contact-invite-header) {
  flex-wrap: wrap;
}

.contact-invite-page :deep(.contact-invite-header h1) {
  flex: 1 1 100%;
  min-width: 0;
}

.contact-invite-page :deep(.contact-invite-header h1 + div) {
  width: 100%;
  min-width: 0;
  flex-shrink: 0;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.contact-invite-page :deep(.contact-invite-header .p-button) {
  min-width: 44px;
  min-height: 44px;
  max-width: 100%;
  flex-shrink: 0;
}

.contact-invite-page :deep(.contact-invite-header .p-button-label) {
  white-space: normal;
  word-break: keep-all;
  overflow-wrap: anywhere;
}

@media (min-width: 640px) {
  .contact-invite-page :deep(.contact-invite-header) {
    flex-wrap: nowrap;
  }

  .contact-invite-page :deep(.contact-invite-header h1) {
    flex: 0 1 auto;
  }

  .contact-invite-page :deep(.contact-invite-header h1 + div) {
    width: auto;
  }
}
</style>
