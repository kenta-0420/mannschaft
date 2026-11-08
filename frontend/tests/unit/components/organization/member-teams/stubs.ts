/**
 * F01.2.1 8-B — member-teams 配下のコンポーネント FE-UT 共通の PrimeVue スタブ。
 * Dialog は Teleport で body に描画されるため、スロットだけ描画する軽量スタブにする。
 */
export const primeStubs = {
  Dialog: {
    props: ['visible', 'header'],
    template: '<div v-if="visible" data-testid="stub-dialog"><slot /><slot name="footer" /></div>',
  },
  Select: {
    props: ['modelValue', 'options', 'optionLabel', 'optionValue', 'optionDisabled', 'placeholder'],
    emits: ['update:modelValue'],
    template: `<select :value="modelValue ?? ''" @change="$emit('update:modelValue', $event.target.value === '' ? null : $event.target.value)">
      <option value="">-</option>
      <option v-for="o in options" :key="o[optionValue]" :value="o[optionValue]" :disabled="o[optionDisabled]">{{ o[optionLabel] }}</option>
    </select>`,
  },
  Textarea: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="text" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Checkbox: {
    props: ['modelValue', 'binary'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
  Message: { template: '<div><slot /></div>' },
  Button: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button type="button" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

export const GROUP_A = '0192a000-0000-7000-8000-00000000000a'
export const GROUP_B = '0192a000-0000-7000-8000-00000000000b'
