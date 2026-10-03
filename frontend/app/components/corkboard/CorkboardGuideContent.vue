<template>
  <div class="space-y-4">
    <!-- セクション1: マイコルクボードとは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-bookmark text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('corkboard.guide.what.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('corkboard.guide.what.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- セクション2: カードを開く -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-external-link text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('corkboard.guide.open.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('corkboard.guide.open.body') }}
          </p>
          <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-violet-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.open.steps.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-violet-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.open.steps.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-violet-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.open.steps.step3') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション3: メモを編集 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-pencil text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('corkboard.guide.editNote.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('corkboard.guide.editNote.body') }}
          </p>
          <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-amber-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.editNote.steps.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-amber-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.editNote.steps.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-amber-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.editNote.steps.step3') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション4: ピン解除・削除 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400">
          <i class="pi pi-trash text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('corkboard.guide.unpinDelete.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('corkboard.guide.unpinDelete.body') }}
          </p>
          <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-red-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.unpinDelete.steps.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-red-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.unpinDelete.steps.step2') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション5: 絞り込み・新規ピン -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-filter text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('corkboard.guide.filterAndPin.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('corkboard.guide.filterAndPin.body') }}
          </p>
          <ol class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-teal-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.filterAndPin.steps.step1') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-teal-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.filterAndPin.steps.step2') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-teal-500" aria-hidden="true" />
              <span>{{ $t('corkboard.guide.filterAndPin.steps.step3') }}</span>
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
