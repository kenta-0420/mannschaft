<template>
  <div class="space-y-4">
    <!-- カード1: 確定シフトを確認する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-calendar text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('my_shift_guide.view_shifts.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('my_shift_guide.view_shifts.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-blue-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.view_shifts.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-blue-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.view_shifts.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-blue-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.view_shifts.steps.step3') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 表示を切り替える -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-sliders-v text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('my_shift_guide.switch_view.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('my_shift_guide.switch_view.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-violet-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.switch_view.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-violet-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.switch_view.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-violet-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.switch_view.steps.step3') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: チームで絞り込む -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-filter text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('my_shift_guide.filter_team.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('my_shift_guide.filter_team.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-teal-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.filter_team.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-teal-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.filter_team.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-teal-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.filter_team.steps.step3') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: シフト詳細を確認する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-info-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('my_shift_guide.view_detail.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('my_shift_guide.view_detail.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.view_detail.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.view_detail.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.view_detail.steps.step3') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード5: 交代依頼を申請する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-share-alt text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('my_shift_guide.swap_request.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('my_shift_guide.swap_request.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-amber-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.swap_request.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-amber-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.swap_request.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-amber-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.swap_request.steps.step3') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-amber-500" aria-hidden="true" />
              <span>{{ $t('my_shift_guide.swap_request.steps.step4') }}</span>
            </li>
          </ul>
          <div class="mt-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="text-xs text-surface-500 dark:text-surface-400">
              {{ $t('my_shift_guide.swap_request.note') }}
            </p>
          </div>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
