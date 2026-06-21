<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細親ページ
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1
 *
 * 役割:
 *   - `/villages/{id}` 直アクセス時のデフォルトタブ（掲示板）へのリダイレクト
 *   - デフォルト = 掲示板タブ (`/villages/{id}/bulletin`)
 *
 * リダイレクトは「ルートミドルウェア」で行う。
 * `<script setup>` トップレベルの `await navigateTo` でリダイレクトすると、
 * SSR（URL直アクセス・更新）では効くが、クライアント側 SPA 遷移では
 * 遷移先ページの Suspense が解決されず白画面になる Nuxt のアンチパターンになるため。
 * ミドルウェアならコンポーネントのマウント前に解決され、SSR/クライアント双方で確実に遷移する。
 *
 * 認証必須。
 */

definePageMeta({
  middleware: [
    'auth',
    (to) => {
      const villageId = String(to.params.id)
      // replace: true で履歴を汚さない（戻る操作で /villages 一覧へ戻れるように）
      return navigateTo(`/villages/${villageId}/bulletin`, { replace: true })
    },
  ],
  layout: 'default',
})
</script>

<template>
  <!-- ミドルウェアでリダイレクトされるため通常は描画されないフォールバック -->
  <PageLoading />
</template>
