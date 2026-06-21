<script setup lang="ts">
/**
 * セキュリティ設定の使い方を案内するモーダルダイアログ。
 *
 * 以前は別ページ（/help/security）として実装していたが、ページ遷移をやめて
 * セキュリティ設定画面上でモーダル表示する方針に変更した。
 *
 * コンテンツ本体は SecurityHelpContent コンポーネントに切り出し、
 * プロジェクト使い方ガイド（ProjectGuideModal）と同じ
 * 「色付き丸アイコン＋カード」方式で見やすくした。
 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('security_help.title')"
    class="w-full max-w-2xl"
    data-testid="security-help-modal"
  >
    <div class="overflow-y-auto py-2">
      <SecurityHelpContent />
    </div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
