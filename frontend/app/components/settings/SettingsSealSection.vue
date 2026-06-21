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
  <SectionCard :title="$t('settings.settings.seal.section_title')">
    <Tabs
      :value="sealActiveTab"
      @update:value="$emit('update:sealActiveTab', $event as string)"
    >
      <TabList>
        <Tab value="0">{{ $t('settings.settings.seal.tab_preview') }}</Tab>
        <Tab value="1">{{ $t('settings.settings.seal.tab_defaults') }}</Tab>
        <Tab value="2">{{ $t('settings.settings.seal.tab_history') }}</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="0">
          <div class="space-y-4">
            <SealPreview :seals="seals" />
            <div class="flex justify-center">
              <Button
                translate="no"
                :label="$t('settings.settings.seal.regenerate_button')"
                icon="pi pi-refresh"
                severity="secondary"
                :loading="regeneratingSeals"
                @click="$emit('regenerateSeals')"
              />
            </div>
            <p class="text-center text-xs text-surface-500">
              {{ $t('settings.settings.seal.regenerate_hint') }}
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
