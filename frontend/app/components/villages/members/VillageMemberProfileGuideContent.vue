<script setup lang="ts">
/**
 * F17.2 Wave3 ⑥ 村人ミニプロフィール Dialog — 使い方ガイド（/手助けモーダル 金型）。
 *
 * `frontend/app/components/project/ProjectGuideContent.vue` を写経した
 * 「色付き丸アイコン＋カード」方式。VillageMemberProfileDialog.vue にインライン
 * 折りたたみ配線される（PageHeader を持たない Dialog 母体のため ②方式）。
 */
</script>

<template>
  <div class="space-y-4">
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-user text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('village.memberProfileGuide.what.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('village.memberProfileGuide.what.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-map text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('village.memberProfileGuide.villages.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('village.memberProfileGuide.villages.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-eye text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('village.memberProfileGuide.toggle.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('village.memberProfileGuide.toggle.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-shield text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('village.memberProfileGuide.privacy.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('village.memberProfileGuide.privacy.body') }}
          </p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
