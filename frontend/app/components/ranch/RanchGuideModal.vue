<script setup lang="ts">
const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    id="ranch-guide-dialog"
    v-model:visible="visible"
    modal
    :header="$t('ranch.guide.title')"
    :draggable="false"
    :close-on-escape="true"
    block-scroll
    class="m-4 w-full max-w-2xl max-h-[90dvh]"
  >
    <div class="min-w-0 space-y-4 overflow-y-auto py-2 text-sm leading-relaxed">
      <p>{{ $t('ranch.avatar.description') }}</p>
      <SectionCard>
        <h2 class="mb-2 text-lg font-semibold">{{ $t('ranch.guide.participationTitle') }}</h2>
        <p>{{ $t('ranch.optional') }}</p>
      </SectionCard>
      <SectionCard>
        <h2 class="mb-2 text-lg font-semibold">{{ $t('ranch.guide.eggTitle') }}</h2>
        <p>{{ $t('ranch.guide.eggBody') }}</p>
        <p class="mt-2">{{ $t('ranch.naming.irreversibleWarning') }}</p>
      </SectionCard>
      <SectionCard>
        <h2 class="mb-2 text-lg font-semibold">{{ $t('ranch.guide.growthTitle') }}</h2>
        <p>{{ $t('ranch.guide.growthBody') }}</p>
        <p class="mt-2">{{ $t('ranch.decorations.permanent') }}</p>
      </SectionCard>
      <SectionCard>
        <h2 class="mb-2 text-lg font-semibold">{{ $t('ranch.guide.safetyTitle') }}</h2>
        <p>{{ $t('ranch.guide.safetyBody') }}</p>
      </SectionCard>
      <SectionCard>
        <h2 class="mb-2 text-lg font-semibold">{{ $t('ranch.guide.availabilityTitle') }}</h2>
        <p>{{ $t('ranch.guide.availabilityBody') }}</p>
      </SectionCard>
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text class="min-h-11 min-w-11" @click="visible = false" />
    </template>
  </Dialog>
</template>
