<template>
  <div class="space-y-4">
    <!-- カード1: シフトスケジュール一覧を確認する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-calendar text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('shift_guide.overview.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('shift_guide.overview.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-blue-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.overview.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-blue-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.overview.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-blue-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.overview.steps.step3') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: 新しいシフトスケジュールを作成する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-plus text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('shift_guide.create.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('shift_guide.create.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.create.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.create.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.create.steps.step3') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.create.steps.step4') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: ステータスを進めてシフトを確定させる -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-check-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('shift_guide.status.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('shift_guide.status.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-teal-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.status.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-teal-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.status.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-teal-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.status.steps.step3') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-teal-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.status.steps.step4') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-teal-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.status.steps.step5') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: メンバーのシフト希望を収集・確認する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-list text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('shift_guide.requests.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('shift_guide.requests.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-amber-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.requests.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-amber-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.requests.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-amber-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.requests.steps.step3') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-amber-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.requests.steps.step4') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード5: スロットと勤務制約を設定する -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-clock text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('shift_guide.slots.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('shift_guide.slots.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-violet-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.slots.steps.step1') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-violet-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.slots.steps.step2') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-violet-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.slots.steps.step3') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-violet-500" aria-hidden="true" />
              <span>{{ $t('shift_guide.slots.steps.step4') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
