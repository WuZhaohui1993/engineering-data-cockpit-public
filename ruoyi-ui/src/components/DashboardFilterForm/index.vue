<template>
  <form class="dashboard-filter-form" @submit.prevent.stop="$emit('submit')" @click.stop @pointerdown.stop>
    <div v-for="field in fields" :key="field.name" class="form-field">
      <label :for="`${formId}-${field.name}`">{{ field.label || field.name }}</label>
      <div v-if="String(field.type).toUpperCase() === 'RADIO'" class="radio-options" role="radiogroup" :aria-label="field.label || field.name">
        <label v-for="(option, index) in dashboardFormOptions(field)" :key="`${option.value}-${index}`">
          <input type="radio" :name="`${formId}-${field.name}`" :value="option.value" :checked="value(field) === option.value" @change="update(field, option.value)" />{{ option.label }}
        </label>
      </div>
      <select v-else-if="String(field.type).toUpperCase() === 'SELECT'" :id="`${formId}-${field.name}`" :value="value(field)" @change="update(field, $event.target.value)">
        <option v-if="!dashboardFormOptions(field).some(option => option.value === '')" value="">{{ field.placeholder || '请选择' }}</option>
        <option v-for="(option, index) in dashboardFormOptions(field)" :key="`${option.value}-${index}`" :value="option.value">{{ option.label }}</option>
      </select>
      <input v-else :id="`${formId}-${field.name}`" :value="value(field)" :type="dashboardFormInputType(field)" :step="String(field.type).toUpperCase() === 'NUMBER' ? 'any' : undefined" :placeholder="field.placeholder || '请输入'" @input="update(field, $event.target.value, false)" @change="$emit('submit')" />
    </div>
    <button type="submit">查询</button>
  </form>
</template>

<script setup>
import { reactive, useId } from 'vue'
import { dashboardFormOptions, dashboardFormInputType } from '@/utils/dashboardPresentation'
const props = defineProps({ fields: { type: Array, default: () => [] }, modelValue: { type: Object, default: () => ({}) } })
const emit = defineEmits(['update:modelValue', 'submit'])
const localValues = reactive({}), formId = useId()
const value = field => String(props.modelValue[field.name] ?? localValues[field.name] ?? field.defaultValue ?? '')
function update(field, next, submit = true) {
  localValues[field.name] = next
  emit('update:modelValue', { ...props.modelValue, ...localValues })
  if (submit) emit('submit')
}
</script>

<style scoped>
.dashboard-filter-form { width: 100%; height: 100%; min-height: 0; margin: 0; display: flex; flex-wrap: wrap; gap: 10px; align-content: var(--widget-content-align-y, center); align-items: flex-end; justify-content: var(--widget-content-justify, var(--widget-align-items, center)); overflow: auto; color: var(--accent, #35d4b0); font-size: var(--widget-font-size, 12px); font-weight: var(--widget-font-weight, 400); text-align: var(--widget-text-align, left); }
.widget-embedded .dashboard-filter-form { --dashboard-form-control-background: transparent; --dashboard-form-control-border: transparent; background: transparent; border-color: transparent; box-shadow: none; }
.form-field { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
input, select, button { min-height: 28px; max-width: 100%; box-sizing: border-box; padding: 4px 7px; border: 1px solid var(--dashboard-form-control-border, color-mix(in srgb, currentColor 50%, transparent)); border-radius: 4px; background: var(--dashboard-form-control-background, color-mix(in srgb, var(--accent, #35d4b0) 8%, transparent)); color: inherit; font: inherit; }
input:not([type='radio']), select { width: 140px; }
option { background: #172437; color: #e5edf6; }
button { padding-inline: 12px; cursor: pointer; }
.radio-options { min-height: 28px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.radio-options label { display: inline-flex; align-items: center; gap: 3px; cursor: pointer; }
input[type='radio'] { width: 14px; height: 14px; min-height: 0; margin: 0; accent-color: var(--accent, #35d4b0); }
</style>
