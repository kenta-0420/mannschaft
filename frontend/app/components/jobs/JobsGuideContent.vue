<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ $t('jobmatching.guide.description') }}
    </p>

    <!-- カード1: チームを選んで求人を探す -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-users text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('jobmatching.guide.team.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('jobmatching.guide.team.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ $t('jobmatching.guide.team.step1') }}</li>
            <li>{{ $t('jobmatching.guide.team.step2') }}</li>
            <li>{{ $t('jobmatching.guide.team.step3') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 求人カードの見方 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-briefcase text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('jobmatching.guide.card.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('jobmatching.guide.card.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('jobmatching.guide.card.item_status') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('jobmatching.guide.card.item_pay') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('jobmatching.guide.card.item_schedule') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: 求人の詳細を確認する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-file text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('jobmatching.guide.detail.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('jobmatching.guide.detail.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ $t('jobmatching.guide.detail.step1') }}</li>
            <li>{{ $t('jobmatching.guide.detail.step2') }}</li>
            <li>{{ $t('jobmatching.guide.detail.step3') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: 応募する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-send text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('jobmatching.guide.apply.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('jobmatching.guide.apply.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ $t('jobmatching.guide.apply.step1') }}</li>
            <li>{{ $t('jobmatching.guide.apply.step2') }}</li>
            <li>{{ $t('jobmatching.guide.apply.step3') }}</li>
            <li>{{ $t('jobmatching.guide.apply.step4') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- カード5: 次のページへ -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-list text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('jobmatching.guide.pagination.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('jobmatching.guide.pagination.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ $t('jobmatching.guide.pagination.step1') }}</li>
            <li>{{ $t('jobmatching.guide.pagination.step2') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
