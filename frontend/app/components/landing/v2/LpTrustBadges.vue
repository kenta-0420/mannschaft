<script setup lang="ts">
const { t } = useI18n()

// すべて実装済みの事実に基づくバッジ（誇張・捏造なし）。
// minor: 18歳未満は保護者連携（ParentalConsentLink 承認）完了まで利用不可（利用規約第4条）
// auth : 二要素認証（認証アプリ）・パスキー対応
// i18n : 6言語対応（ja/en/de/es/ko/zh）
// encryption: 通信・データの暗号化
// gdpr : GDPR 対応（退会時の匿名化・データエクスポート）
// watch: 子ども（見守りモード）・高齢の家族（ケアリンク）の見守り。年齢による自動発動はせず同意にもとづく
const badges = [
  { key: 'minor', icon: 'pi pi-users' },
  { key: 'auth', icon: 'pi pi-lock' },
  { key: 'i18n', icon: 'pi pi-globe' },
  { key: 'encryption', icon: 'pi pi-shield' },
  { key: 'gdpr', icon: 'pi pi-verified' },
  { key: 'watch', icon: 'pi pi-eye' },
]
</script>

<template>
  <section id="lp-trust" aria-labelledby="lp-trust-heading" class="py-10 dark:bg-surface-900">
    <div class="mx-auto max-w-3xl px-4 text-center">
      <h2 id="lp-trust-heading" class="mb-5 text-sm font-semibold tracking-wide text-surface-500 dark:text-surface-400">
        {{ t('landing.v2.trust.heading') }}
      </h2>
      <ul class="flex flex-wrap items-center justify-center gap-2.5">
        <li
          v-for="badge in badges"
          :key="badge.key"
          class="inline-flex items-center gap-2 rounded-full border border-surface-200 bg-surface-50 px-4 py-2 text-xs font-medium text-surface-600 dark:border-surface-700 dark:bg-surface-800 dark:text-surface-300"
        >
          <i :class="[badge.icon, 'text-primary']" />
          {{ t(`landing.v2.trust.badges.${badge.key}`) }}
        </li>
      </ul>
    </div>
  </section>
</template>
