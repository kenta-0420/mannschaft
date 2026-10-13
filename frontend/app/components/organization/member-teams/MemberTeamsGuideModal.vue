<script setup lang="ts">
/**
 * F01.2.1 8-B — 所属チーム画面の「加盟申請・送った招待・申請の制限」の使い方モーダル（G143）。
 * v-model:visible で開閉。「色付き丸アイコン＋カード」方式。i18n: orgMemberTeams.guide.*
 */
const visible = defineModel<boolean>('visible', { default: false })

const sections = [
  { key: 'applications', icon: 'pi-inbox', color: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400' },
  { key: 'invites', icon: 'pi-send', color: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400' },
  { key: 'restrictions', icon: 'pi-ban', color: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400' },
] as const
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('orgMemberTeams.guide.title')"
    class="w-full max-w-2xl"
    data-testid="member-teams-guide-modal"
  >
    <div class="space-y-4 overflow-y-auto py-2">
      <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
        {{ $t('orgMemberTeams.guide.description') }}
      </p>

      <SectionCard v-for="section in sections" :key="section.key">
        <div class="flex items-start gap-4">
          <div
            class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full"
            :class="section.color"
          >
            <i class="pi text-xl" :class="section.icon" aria-hidden="true" />
          </div>
          <div class="w-full">
            <h2 class="mb-2 text-lg font-semibold">
              {{ $t(`orgMemberTeams.guide.${section.key}.title`) }}
            </h2>
            <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ $t(`orgMemberTeams.guide.${section.key}.body`) }}
            </p>
          </div>
        </div>
      </SectionCard>
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
