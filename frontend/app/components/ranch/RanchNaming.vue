<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import { isRanchNameValid, normalizeRanchName, ranchNameLength } from '~/utils/ranch-name'
const props = defineProps<{ busy?: boolean; retryRequired?: boolean }>()
const emit = defineEmits<{ confirm: [name: string] }>()
const { t } = useI18n()
const { defineField, errors, handleSubmit } = useForm({ validationSchema: toTypedSchema(z.object({ name: z.string().refine(isRanchNameValid, () => ({ message: t('ranch.naming.invalid') })) })), initialValues: { name: '' } })
const [name, nameAttrs] = defineField('name')
const composing = ref(false); const checkedName = ref<string | null>(null)
const confirmationText = ref<HTMLElement | null>(null)
const input = ref<{ $el?: HTMLInputElement } | null>(null)
const count = computed(() => composing.value ? null : ranchNameLength(name.value ?? ''))
const check = handleSubmit(values => { if (!composing.value) { checkedName.value = normalizeRanchName(values.name); nextTick(() => confirmationText.value?.focus()) } })
function back() { if (props.retryRequired) return; checkedName.value = null; nextTick(() => input.value?.$el?.focus()) }
</script>
<template>
 <SectionCard :title="t('ranch.naming.title')">
  <p id="ranch-name-warning" class="mb-3">{{ t('ranch.naming.irreversibleWarning') }}</p>
  <form v-if="checkedName === null" class="space-y-3" @submit.prevent="check">
   <label for="ranch-name" class="block">{{ t('ranch.naming.nameLabel') }}</label>
   <InputText id="ranch-name" ref="input" v-model="name" v-bind="nameAttrs" class="w-full text-base" aria-describedby="ranch-name-warning ranch-name-count ranch-name-error" :disabled="busy" @compositionstart="composing = true" @compositionend="composing = false" />
   <p id="ranch-name-count">{{ count === null ? '' : t('ranch.naming.characterCount', { count }) }}</p>
   <p id="ranch-name-error" role="alert">{{ composing ? '' : errors.name }}</p>
   <Button type="submit" class="min-h-11" :label="t('ranch.naming.checkName')" :disabled="composing || busy" />
  </form>
  <div v-else class="space-y-3" role="group" :aria-label="t('ranch.naming.title')">
   <p ref="confirmationText" tabindex="-1">{{ t('ranch.naming.confirmPrompt', { name: checkedName }) }}</p>
   <p v-if="retryRequired" role="status">{{ t('ranch.command.retryRequired') }}</p>
   <div class="flex flex-wrap gap-3">
    <Button class="min-h-11" :label="t('ranch.naming.confirm')" :loading="busy" @click="emit('confirm', checkedName)" />
    <Button class="min-h-11" :label="t('ranch.naming.back')" outlined :disabled="busy || retryRequired" @click="back" />
   </div>
  </div>
 </SectionCard>
</template>
