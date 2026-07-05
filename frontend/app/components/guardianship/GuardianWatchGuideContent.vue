<!--
  F08.9 件2/件3 見守り画面の使い方ガイド（カード方式）。
  GuardianWatchGuideModal から差し込まれるコンテンツ本体。
  参考: components/project/ProjectGuideContent.vue（マイプロジェクトの手本）
-->
<template>
  <div class="space-y-4">
    <!-- セクション1: 閲覧専用です -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-eye text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('proxy.guardianship.watch.guide.readonly.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('proxy.guardianship.watch.guide.readonly.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- セクション2: 12歳未満のみ対象 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-shield text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('proxy.guardianship.watch.guide.ageLimit.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('proxy.guardianship.watch.guide.ageLimit.body') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- セクション3: 代理マークの意味 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-user-edit text-xl" aria-hidden="true" />
        </div>
        <div>
          <h2 class="mb-2 text-lg font-semibold">
            {{ $t('proxy.guardianship.watch.guide.proxyBadge.title') }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ $t('proxy.guardianship.watch.guide.proxyBadge.body') }}
          </p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
