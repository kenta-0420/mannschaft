<script setup lang="ts">
import type { ElectronicSeal, ScopeDefault } from '~/types/seal'

defineProps<{
  seals: ElectronicSeal[]
  scopeDefaults: ScopeDefault[]
  regeneratingSeals: boolean
  sealActiveTab: string
  userId: number | undefined
}>()

defineEmits<{
  regenerateSeals: []
  saveDefaults: [defaults: ScopeDefault[]]
  'update:sealActiveTab': [value: string]
}>()
</script>

<template>
  <SectionCard title="電子印鑑">
    <Tabs
      :value="sealActiveTab"
      @update:value="$emit('update:sealActiveTab', $event as string)"
    >
      <TabList>
        <Tab value="0">印鑑プレビュー</Tab>
        <Tab value="1">デフォルト設定</Tab>
        <Tab value="2">押印履歴</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="0">
          <div class="space-y-4">
            <SealPreview :seals="seals" />
            <div class="flex justify-center">
              <Button
                label="印鑑を再生成"
                icon="pi pi-refresh"
                severity="secondary"
                :loading="regeneratingSeals"
                @click="$emit('regenerateSeals')"
              />
            </div>
            <p class="text-center text-xs text-surface-500">
              印鑑は登録姓名から自動生成されます（1時間に3回まで）
            </p>
          </div>
        </TabPanel>
        <TabPanel value="1">
          <SealScopeDefaults :defaults="scopeDefaults" @save="$emit('saveDefaults', $event)" />
        </TabPanel>
        <TabPanel value="2">
          <StampLog v-if="userId" :user-id="userId" />
        </TabPanel>
      </TabPanels>
    </Tabs>
  </SectionCard>
</template>
