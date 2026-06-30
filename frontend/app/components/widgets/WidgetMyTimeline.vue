<script setup lang="ts">
/**
 * 個人ダッシュボード集約タイムライン Widget。
 *
 * <p>ログインユーザーが所属する全チーム/組織（MEMBER / SUPPORTER 両方）の timeline 投稿を
 * 横断集約して新しい順で表示する（GET /api/v1/timeline/my）。VILLAGE は集約対象外・自分の投稿も含む。</p>
 *
 * <p>実体は {@link TimelineFeed} に {@code my-feed} を渡して描画する（pinned は常に空・実カーソル）。
 * カードフッターに「もっと見る」導線（{@code /timeline}）を置く。</p>
 */
const { t } = useI18n()
</script>

<template>
  <DashboardWidgetCard
    :title="t('dashboard.widget_labels.my-timeline')"
    icon="pi pi-comments"
    :col-span="2"
    :to="'/timeline'"
  >
    <TimelineFeed my-feed />
    <div class="flex justify-end pt-3">
      <NuxtLink
        to="/timeline"
        class="text-sm text-primary hover:underline"
      >
        {{ t('common.button.see_more') }}
      </NuxtLink>
    </div>
  </DashboardWidgetCard>
</template>
