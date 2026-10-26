<template>
  <div class="space-y-4">
    <!-- セクション1: プロジェクトとは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-briefcase text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('project.guide.what.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('project.guide.what.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- セクション2: プロジェクトの作り方 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-plus-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('project.guide.create.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('project.guide.create.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('project.guide.create.item_title') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('project.guide.create.item_emoji') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('project.guide.create.item_color') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('project.guide.create.item_due_date') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- セクション3: マイルストーンとゲート -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-flag text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('project.guide.milestone.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('project.guide.milestone.body') }}
          </p>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="mb-1 text-xs font-medium text-surface-700 dark:text-surface-200">
              {{ $t('project.guide.milestone.completion_mode_label') }}
            </p>
            <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
              <li>
                <span class="font-medium">{{ $t('project.guide.milestone.mode_auto') }}</span>
                — {{ $t('project.guide.milestone.mode_auto_desc') }}
              </li>
              <li>
                <span class="font-medium">{{ $t('project.guide.milestone.mode_manual') }}</span>
                — {{ $t('project.guide.milestone.mode_manual_desc') }}
              </li>
            </ul>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- セクション4: 進捗の見方 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-chart-bar text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('project.guide.progress.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('project.guide.progress.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- セクション5: 個人プロジェクトとチームプロジェクト -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-users text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('project.guide.scope.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('project.guide.scope.body') }}
          </p>
          <div class="grid gap-3 sm:grid-cols-2">
            <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
              <p class="mb-1 text-xs font-semibold text-surface-700 dark:text-surface-200">
                {{ $t('project.guide.scope.personal_title') }}
              </p>
              <p class="text-xs text-surface-500 dark:text-surface-400">
                {{ $t('project.guide.scope.personal_desc') }}
              </p>
            </div>
            <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
              <p class="mb-1 text-xs font-semibold text-surface-700 dark:text-surface-200">
                {{ $t('project.guide.scope.team_title') }}
              </p>
              <p class="text-xs text-surface-500 dark:text-surface-400">
                {{ $t('project.guide.scope.team_desc') }}
              </p>
            </div>
          </div>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
