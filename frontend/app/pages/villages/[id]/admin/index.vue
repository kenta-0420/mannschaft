<script setup lang="ts">
/**
 * F17.1 P3 — 村長コンソール（`/villages/[id]/admin`）カードグリッド L2 ハブ。
 *
 * 設計書: docs/features/F17.1_village_headman_console_and_recruit_categories.md
 *   §3.1（金型）/ §3.2（admin-console ミドルウェア流用不可）/ §3.3（独立画面）/
 *   §3.4（カード構成と権限差）
 *
 * # 金型
 *  カードグリッド方式は `pages/teams/[slug]/admin/index.vue` を踏襲する（§3.1）。
 *  ただし村ドメインは `admin-console` ミドルウェアを流用できない（§3.2 — params.slug 前提
 *  ＋ useRoleAccess が 'team'|'organization' 固定のため）。権限は永続シェルが解決済みの
 *  `useVillageContext().perms` を使い、専用ミドルウェアは作らない。
 *
 * # 永続シェル方式（SPA）
 *  村データ・権限は親 `pages/villages/[id].vue` が 1 度だけ取得し provide 済み。
 *  本ページは `useVillageContext()` で inject するのみで、村の再フェッチは行わない。
 *  VillageHeader も親が常駐描画するため、本ページ遷移時に再フェッチ・再マウントは発生しない
 *  （AC-34）。
 *
 * # タブに追加しない（§3.3）
 *  9 タブ列（VillageHeader.vue）には追加しない。導線は VillageHeader の歯車アイコン
 *  「村の運営」ボタン（isAdmin のときのみ表示）。activeTab は未知セグメントとして
 *  bulletin ハイライト既定に落ちる（既存の newsletter-settings / join-request と同じ
 *  不整合を踏襲。是正は §10 P7 の任意仕上げ）。
 *
 * # カード構成と権限差（§3.4）
 *  コンソール本体の表示権限は isAdmin（HEADMAN or ELDER）。カード1〜5 は長老にも見え、
 *  カード6「村の基本設定」のみ isHeadman（MANAGE）で出し分ける。
 *  カード2「ニュースレター設定」は Q2 が 2026-07-16 に御裁可され **(b) 長老も可** で決着した
 *  ため isAdmin とする（BE `VillageNewsletterService` は元々 ELDER を許可しており、
 *  これで FE/BE の権限不一致も解消する。§12.1 / §12.2）。
 *  カード1「募集カテゴリ」・カード5「通報管理」は本設計の後続フェーズ（P4 / 別設計）で
 *  実装されるため、遷移先ページが無い間は `to: null` の「近日公開」プレースホルダとする
 *  （`teams/[slug]/admin/index.vue` の approvals カードと同じ作法）。
 */
// NuxtLink は #components から明示 import して `<component :is>` に **コンポーネント実体**を渡す。
//
// 【重要・実機で踏んだ罠】`:is="'NuxtLink'"`（文字列）は動かない。Vue は文字列を
// resolveDynamicComponent → ローカル/グローバル登録の解決に掛けるが、Nuxt の components
// 自動 import は「テンプレートに `<NuxtLink>` というタグが literal で現れる」ことを引き金に
// import を注入する仕組みのため、`:is` の文字列だけでは登録されない。解決に失敗した文字列は
// **そのままネイティブ要素名として描画される**ので、`<nuxtlink>` という未知要素（href 無し・
// クリックしても遷移しない死んだリンク）が静かに出来上がる。TypeScript も lint も検知しない。
import type { Component } from 'vue'
import { NuxtLink } from '#components'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()

const villageId = computed<string>(() => String(route.params.id))

// 村本体・権限は親シェルから inject（再フェッチしない）
const { village, perms, openEditDialog } = useVillageContext()

interface AdminConsoleCard {
  key: string
  titleKey: string
  descKey: string
  icon: string
  /** 遷移先ルート。未整備カードは null（近日公開プレースホルダ）。 */
  to: string | null
  /** ルート遷移ではなく親シェルのダイアログを開くカード（村の基本設定）。 */
  action?: () => void
}

const base = computed(() => `/villages/${villageId.value}`)

const cards = computed<AdminConsoleCard[]>(() => {
  const list: (AdminConsoleCard & { visible: boolean })[] = [
    {
      key: 'recruitCategories',
      titleKey: 'village.admin.cards.recruitCategories.title',
      descKey: 'village.admin.cards.recruitCategories.desc',
      icon: 'pi pi-tags',
      // P4（カテゴリ管理画面）が未着手のため、画面が出来るまでは近日公開プレースホルダ。
      to: null,
      visible: perms.value.isAdmin,
    },
    {
      key: 'newsletter',
      titleKey: 'village.admin.cards.newsletter.title',
      descKey: 'village.admin.cards.newsletter.desc',
      icon: 'pi pi-envelope',
      to: `${base.value}/newsletter-settings`,
      // Q2 御裁可済（2026-07-16・設計書 §12.1）: (b) 長老も可。BE は元々 ELDER 許可のため BE 変更不要。
      visible: perms.value.isAdmin,
    },
    {
      key: 'members',
      titleKey: 'village.admin.cards.members.title',
      descKey: 'village.admin.cards.members.desc',
      icon: 'pi pi-users',
      to: `${base.value}/members`,
      visible: perms.value.isAdmin,
    },
    {
      key: 'joinRequests',
      titleKey: 'village.admin.cards.joinRequests.title',
      descKey: 'village.admin.cards.joinRequests.desc',
      icon: 'pi pi-user-plus',
      to: `${base.value}/join-request`,
      visible: perms.value.isAdmin,
    },
    {
      key: 'reports',
      titleKey: 'village.admin.cards.reports.title',
      descKey: 'village.admin.cards.reports.desc',
      icon: 'pi pi-flag',
      // 通報管理画面は本設計の対象外（別設計・§2.2）。当面は近日公開プレースホルダ。
      to: null,
      visible: perms.value.isAdmin,
    },
    {
      key: 'settings',
      titleKey: 'village.admin.cards.settings.title',
      descKey: 'village.admin.cards.settings.desc',
      icon: 'pi pi-cog',
      to: null,
      // 村本体編集ダイアログは親シェルが常駐描画。ここでは開閉のみ委譲する。
      action: openEditDialog,
      visible: perms.value.isHeadman,
    },
  ]
  return list.filter(c => c.visible)
})

/**
 * カードを何で描画するか。
 * - 遷移先あり → NuxtLink（**文字列 'NuxtLink' ではなくコンポーネント実体**。上記 import の注参照）
 * - ダイアログ起動 → button
 * - どちらも無い（近日公開） → div
 */
function cardTag(card: AdminConsoleCard): Component | string {
  if (card.to) return NuxtLink
  if (card.action) return 'button'
  return 'div'
}

function cardAttrs(card: AdminConsoleCard): Record<string, unknown> {
  if (card.to) return { to: card.to }
  if (card.action) return { type: 'button', onClick: card.action }
  return {}
}

// =============================================================================
// 使い方モーダル
// =============================================================================

const showGuide = ref(false)
</script>

<template>
  <div class="mx-auto max-w-5xl p-6">
    <PageHeader
      :title="t('village.admin.title')"
      size="sm"
      help
      :back-to="`/villages/${villageId}`"
      @help="showGuide = true"
    >
      <template v-if="village" #actions>
        <span class="text-sm text-surface-500">{{ village.name }}</span>
      </template>
    </PageHeader>

    <p class="mb-6 text-sm text-surface-600 dark:text-surface-300">
      {{ t('village.admin.subtitle') }}
    </p>

    <!-- 権限不足（VILLAGER / VISITOR。AC-32） -->
    <Message v-if="!perms.isAdmin" severity="warn" :closable="false" data-testid="village-admin-access-denied">
      {{ t('village.admin.accessDenied') }}
    </Message>

    <!-- カードグリッド（HEADMAN / ELDER。teams/[slug]/admin/index.vue と同じ方式・§3.1） -->
    <section v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" data-testid="village-admin-console-grid">
      <component
        :is="cardTag(card)"
        v-for="card in cards"
        :key="card.key"
        v-bind="cardAttrs(card)"
        :data-testid="`village-admin-card-${card.key}`"
        :class="[
          'relative flex flex-col rounded-xl border p-5 text-left shadow-sm transition',
          (card.to || card.action)
            ? 'border-surface-200 bg-white hover:border-primary-400 hover:shadow-md dark:border-surface-700 dark:bg-surface-900'
            : 'cursor-default border-dashed border-surface-300 bg-surface-50 dark:border-surface-600 dark:bg-surface-800/40',
        ]"
      >
        <div class="flex items-center gap-2">
          <i :class="[card.icon, 'text-lg text-primary-600 dark:text-primary-400']" aria-hidden="true" />
          <h2 class="text-base font-semibold">
            {{ t(card.titleKey) }}
          </h2>
        </div>
        <p class="mt-2 flex-1 text-sm text-surface-600 dark:text-surface-400">
          {{ t(card.descKey) }}
        </p>
        <span
          v-if="card.to || card.action"
          class="mt-3 inline-block text-sm font-medium text-primary-600 dark:text-primary-400"
        >
          &rarr;
        </span>
        <span
          v-else
          class="mt-3 inline-block text-xs font-medium text-surface-400 dark:text-surface-500"
        >
          {{ t('village.admin.comingSoon') }}
        </span>
      </component>
    </section>

    <!-- 使い方モーダル -->
    <VillageAdminGuideModal v-model:visible="showGuide" />
  </div>
</template>
