<script setup lang="ts">
const { t } = useI18n()

/**
 * "ラベル — 説明" 形式のi18n値からラベル部分（前半）を取り出す。
 */
function splitLabel(key: string): string {
  return t(key).split('—')[0]?.trim() ?? ''
}

/**
 * "ラベル — 説明" 形式のi18n値から説明部分（後半）を取り出す。
 */
function splitDesc(key: string): string {
  return t(key).split('—').slice(1).join('—').trim()
}
</script>

<template>
  <div class="space-y-4">
    <!-- カード1: 募集を探す -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-search text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('matching.guide.browse.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('matching.guide.browse.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- カード2: ステータスの見かた -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-info-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('matching.guide.status.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('matching.guide.status.body') }}
          </p>
          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
              <li>
                <span class="font-medium">{{ splitLabel('matching.guide.status.open') }}</span>
                — {{ splitDesc('matching.guide.status.open') }}
              </li>
              <li>
                <span class="font-medium">{{ splitLabel('matching.guide.status.matched') }}</span>
                — {{ splitDesc('matching.guide.status.matched') }}
              </li>
              <li>
                <span class="font-medium">{{ splitLabel('matching.guide.status.cancelled') }}</span>
                — {{ splitDesc('matching.guide.status.cancelled') }}
              </li>
            </ul>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- カード3: 種別・カテゴリ・レベル -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-tag text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('matching.guide.types.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('matching.guide.types.body') }}
          </p>
          <ul class="space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('matching.guide.types.competition') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('matching.guide.types.practice') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('matching.guide.types.exchange') }}</span>
            </li>
            <li class="flex items-center gap-2">
              <i class="pi pi-check text-green-500" aria-hidden="true" />
              <span>{{ $t('matching.guide.types.recruit') }}</span>
            </li>
          </ul>
        </div>
      </div>
    </SectionCard>

    <!-- カード4: 自チームから募集を出す -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-plus text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('matching.guide.create.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('matching.guide.create.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li>{{ $t('matching.guide.create.step1') }}</li>
            <li>{{ $t('matching.guide.create.step2') }}</li>
            <li>{{ $t('matching.guide.create.step3') }}</li>
            <li>{{ $t('matching.guide.create.step4') }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
