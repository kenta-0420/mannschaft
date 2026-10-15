<script setup lang="ts">
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="$t('settings.linked_accounts.guide.title')"
    modal
    :style="{ width: '32rem' }"
  >
    <div class="space-y-4">
      <div class="rounded-lg border border-surface-100 p-4 dark:border-surface-600">
        <div class="mb-2 flex items-center gap-2">
          <i class="pi pi-google text-xl text-blue-500" />
          <h3 class="font-semibold">{{ $t('settings.linked_accounts.guide.google_title') }}</h3>
        </div>
        <p class="text-sm text-surface-600 dark:text-surface-300">
          {{ $t('settings.linked_accounts.guide.google_desc') }}
        </p>
      </div>
      <div class="rounded-lg border border-surface-100 p-4 dark:border-surface-600">
        <div class="mb-2 flex items-center gap-2">
          <i class="pi pi-apple text-xl" />
          <h3 class="font-semibold">{{ $t('settings.linked_accounts.guide.apple_title') }}</h3>
        </div>
        <p class="text-sm text-surface-600 dark:text-surface-300">
          {{ $t('settings.linked_accounts.guide.apple_desc') }}
        </p>
      </div>
      <div class="rounded-lg border border-surface-100 p-4 dark:border-surface-600">
        <div class="mb-2 flex items-center gap-2">
          <i class="pi pi-comment text-xl text-green-500" />
          <h3 class="font-semibold">{{ $t('settings.linked_accounts.guide.line_title') }}</h3>
        </div>
        <p class="text-sm text-surface-600 dark:text-surface-300">
          {{ $t('settings.linked_accounts.guide.line_desc') }}
        </p>
      </div>
    </div>
  </Dialog>
</template>
