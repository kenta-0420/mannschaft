<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ $t('friends.guide.description') }}
    </p>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-users text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('friends.guide.what.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('friends.guide.what.body') }}
          </p>
          <div class="flex items-start gap-2 rounded-lg bg-surface-50 p-3 text-sm text-surface-600 dark:bg-surface-800 dark:text-surface-300">
            <i class="pi pi-info-circle mt-0.5" aria-hidden="true" />
            <span>{{ $t('friends.guide.what.note') }}</span>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-user-plus text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('friends.guide.follow.title') }}
          </h2>
          <ol class="mb-3 space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold dark:bg-surface-700">1</span>
              <span>{{ $t('friends.guide.follow.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold dark:bg-surface-700">2</span>
              <span>{{ $t('friends.guide.follow.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold dark:bg-surface-700">3</span>
              <span>{{ $t('friends.guide.follow.step3') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold dark:bg-surface-700">4</span>
              <span>{{ $t('friends.guide.follow.step4') }}</span>
            </li>
          </ol>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('friends.guide.follow.mutual') }}
          </p>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="mb-1 text-xs font-medium text-surface-700 dark:text-surface-200">
              {{ $t('friends.guide.follow.caution_title') }}
            </p>
            <ul class="list-disc space-y-1 pl-4 text-sm text-surface-600 dark:text-surface-300">
              <li>{{ $t('friends.guide.follow.caution_self') }}</li>
              <li>{{ $t('friends.guide.follow.caution_already') }}</li>
              <li>{{ $t('friends.guide.follow.caution_busy') }}</li>
              <li>{{ $t('friends.guide.follow.caution_role') }}</li>
            </ul>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-eye text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('friends.guide.visibility.title') }}
          </h2>
          <ol class="mb-3 space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold dark:bg-surface-700">1</span>
              <span>{{ $t('friends.guide.visibility.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold dark:bg-surface-700">2</span>
              <span>{{ $t('friends.guide.visibility.step2') }}</span>
            </li>
          </ol>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="mb-1 text-xs font-medium text-surface-700 dark:text-surface-200">
              {{ $t('friends.guide.visibility.caution_title') }}
            </p>
            <ul class="list-disc space-y-1 pl-4 text-sm text-surface-600 dark:text-surface-300">
              <li>{{ $t('friends.guide.visibility.caution_admin') }}</li>
              <li>{{ $t('friends.guide.visibility.caution_revert') }}</li>
            </ul>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-user-minus text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('friends.guide.unfollow.title') }}
          </h2>
          <ol class="mb-3 space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold dark:bg-surface-700">1</span>
              <span>{{ $t('friends.guide.unfollow.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold dark:bg-surface-700">2</span>
              <span>{{ $t('friends.guide.unfollow.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <span class="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-100 text-xs font-semibold dark:bg-surface-700">3</span>
              <span>{{ $t('friends.guide.unfollow.step3') }}</span>
            </li>
          </ol>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="mb-1 text-xs font-medium text-surface-700 dark:text-surface-200">
              {{ $t('friends.guide.unfollow.caution_title') }}
            </p>
            <ul class="list-disc space-y-1 pl-4 text-sm text-surface-600 dark:text-surface-300">
              <li>{{ $t('friends.guide.unfollow.caution_kept') }}</li>
              <li>{{ $t('friends.guide.unfollow.caution_role') }}</li>
            </ul>
          </div>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-sitemap text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('friends.guide.related.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('friends.guide.related.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-1 text-teal-500" aria-hidden="true" />
              <span>{{ $t('friends.guide.related.item_folders') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-1 text-teal-500" aria-hidden="true" />
              <span>{{ $t('friends.guide.related.item_feed') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-1 text-teal-500" aria-hidden="true" />
              <span>{{ $t('friends.guide.related.item_exports') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
