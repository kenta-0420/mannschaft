<script setup lang="ts">
const { t } = useI18n()
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('contact_invite_guide.description') }}
    </p>

    <!-- 招待URLとは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-link text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">{{ t('contact_invite_guide.what.title') }}</h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_invite_guide.what.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- URLを発行する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-plus-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('contact_invite_guide.create.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_invite_guide.create.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ t('contact_invite_guide.create.step1') }}</li>
            <li>{{ t('contact_invite_guide.create.step2') }}</li>
            <li>{{ t('contact_invite_guide.create.step3') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- 相手に共有する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-share-alt text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('contact_invite_guide.share.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_invite_guide.share.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ t('contact_invite_guide.share.step1') }}</li>
            <li>{{ t('contact_invite_guide.share.step2') }}</li>
            <li>{{ t('contact_invite_guide.share.step3') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- 利用状況の確認と無効化 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-shield text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t('contact_invite_guide.manage.title') }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('contact_invite_guide.manage.body') }}
          </p>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="text-sm text-surface-600 dark:text-surface-300">
              <i class="pi pi-info-circle mr-1 text-amber-500" aria-hidden="true" />{{ t('contact_invite_guide.manage.note') }}
            </p>
          </div>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
