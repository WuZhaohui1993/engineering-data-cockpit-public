<template>
  <div
    class="designer-color-picker"
    :class="$attrs.class"
    :style="$attrs.style"
  >
    <el-color-picker
      v-bind="$attrs"
      :model-value="modelValue"
      :disabled="disabled"
      :color-format="colorFormat"
      @update:model-value="onUpdateModelValue"
      @change="onChange"
      @active-change="onActiveChange"
      @focus="emit('focus', $event)"
      @blur="emit('blur', $event)"
    />
    <button
      v-if="!disabled"
      type="button"
      class="designer-color-picker__eyedropper"
      :disabled="!eyeDropperSupported || picking"
      :aria-label="eyeDropperSupported ? '从屏幕取色' : '当前浏览器不支持屏幕取色'"
      title="从屏幕取色"
      @click.stop="pickFromScreen"
    >
      <el-icon :class="{ 'is-loading': picking }"><Aim /></el-icon>
    </button>
    <el-input
      class="designer-color-picker__value"
      :model-value="draftValue"
      :disabled="disabled"
      :size="$attrs.size"
      :placeholder="$attrs.placeholder || '输入颜色值'"
      :title="draftValue"
      aria-label="颜色值"
      clearable
      @update:model-value="onTextInput"
      @focus="onTextFocus"
      @blur="onTextBlur"
      @clear="commitText"
      @keydown="onTextKeydown"
      @keydown.enter.stop.prevent="commitText"
      @keydown.esc.stop.prevent="cancelText"
    />
  </div>
</template>

<script setup>
import { computed, onScopeDispose, ref, watch } from "vue";
import { Aim } from "@element-plus/icons-vue";

defineOptions({ inheritAttrs: false });

const props = defineProps({
  modelValue: {
    type: String,
    default: undefined,
  },
  disabled: {
    type: Boolean,
    default: false,
  },
  colorFormat: {
    type: String,
    default: undefined,
  },
});

const emit = defineEmits([
  "update:modelValue",
  "change",
  "active-change",
  "focus",
  "blur",
]);

const picking = ref(false);
const draftValue = ref(props.modelValue || "");
const textFocused = ref(false);
const textDirty = ref(false);
let modelRevision = 0;
let eyeDropperController;
let disposed = false;

function resetText(value = props.modelValue) {
  draftValue.value = value || "";
  textDirty.value = false;
}

watch(() => props.modelValue, (value) => {
  modelRevision += 1;
  eyeDropperController?.abort();
  resetText(value);
}, { flush: "sync" });

onScopeDispose(() => {
  disposed = true;
  eyeDropperController?.abort();
});

// Accept the persisted dashboard color formats, never arbitrary CSS values.
function normalizeColor(value) {
  const text = String(value ?? "").trim();
  if (text.length > 64) return null;
  if (!text || /^transparent$/i.test(text)) return text.toLowerCase();
  if (/^#(?:[\da-f]{3}|[\da-f]{4}|[\da-f]{6}|[\da-f]{8})$/i.test(text)) return text;
  const functional = text.match(/^(rgb|rgba)\(([^()]*)\)$/i);
  if (!functional) return null;
  const parts = functional[2].split(",").map(part => part.trim());
  const name = functional[1].toLowerCase();
  if (parts.length !== (name === "rgba" ? 4 : 3)) return null;
  if (!parts.every(part => /^(?:\d+(?:\.\d+)?|\.\d+)%?$/.test(part))) return null;
  const channels = parts.slice(0, 3);
  if (channels.some(part => part.endsWith("%") !== channels[0].endsWith("%"))) return null;
  if (channels.some(part => parseFloat(part) > (part.endsWith("%") ? 100 : 255))) return null;
  if (parts[3] && parseFloat(parts[3]) > (parts[3].endsWith("%") ? 100 : 1)) return null;
  const normalized = `${name}(${parts.join(", ")})`;
  return normalized.length <= 64 ? normalized : null;
}

function formatCommittedColor(value) {
  // Dashboard fields persist transparent colors as RGBA, while some chart
  // options only accept HEX. Keep existing values unchanged until edited.
  const color = value === "transparent" ? "rgba(0, 0, 0, 0)" : value;
  if (props.colorFormat !== "hex" || color.startsWith("#")) return color;
  const functional = color.match(/^rgba?\(([^()]*)\)$/);
  if (!functional) return color;
  const parts = functional[1].split(",").map(part => part.trim());
  const byte = value => Math.round(value).toString(16).padStart(2, "0");
  const channels = parts.slice(0, 3).map(part =>
    byte(part.endsWith("%") ? parseFloat(part) / 100 * 255 : parseFloat(part)),
  );
  if (parts.length === 4) {
    const alpha = parts[3];
    channels.push(byte((alpha.endsWith("%") ? parseFloat(alpha) / 100 : parseFloat(alpha)) * 255));
  }
  return `#${channels.join("")}`;
}

function commitColor(value) {
  value = formatCommittedColor(value);
  resetText(value);
  emit("update:modelValue", value);
  // Some fields intentionally use active-change without v-model.
  emit("active-change", value);
  emit("change", value);
}

function onTextInput(value) {
  if (props.disabled) return;
  draftValue.value = value;
  textDirty.value = true;
}

function commitText() {
  if (props.disabled || !textDirty.value) return;
  const normalized = normalizeColor(draftValue.value);
  const value = normalized === null ? null : formatCommittedColor(normalized);
  if (value === null || value === (props.modelValue || "")) {
    resetText();
    return;
  }
  eyeDropperController?.abort();
  commitColor(value);
}

function cancelText() {
  resetText();
}

function onTextKeydown(event) {
  if ((event.metaKey || event.ctrlKey) && event.key?.toLowerCase() === "s") {
    // Commit before the designer's existing save shortcut receives this event.
    // Keep bubbling so it remains the only place that performs the save.
    commitText();
  }
}

function onTextFocus(event) {
  textFocused.value = true;
  emit("focus", event);
}

function onTextBlur(event) {
  commitText();
  textFocused.value = false;
  emit("blur", event);
}

function preserveAlpha(color, current = String(props.modelValue || "")) {
  const normalized = normalizeColor(current);
  const rgba = normalized?.match(/^rgba\([^,]+,[^,]+,[^,]+,\s*([\d.%]+)\)$/);
  const alpha = normalized === "transparent" ? "0" : rgba?.[1];
  if (alpha && parseFloat(alpha) < (alpha.endsWith("%") ? 100 : 1)) {
    const value = color.slice(1);
    const r = parseInt(value.slice(0, 2), 16);
    const g = parseInt(value.slice(2, 4), 16);
    const b = parseInt(value.slice(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  }
  const hex = normalized?.match(/^#([\da-f]{4}|[\da-f]{8})$/i);
  if (!hex) return color;
  const hexAlpha = hex[1].length === 4 ? hex[1][3].repeat(2) : hex[1].slice(6);
  return `${color}${hexAlpha}`;
}

const eyeDropperSupported = computed(() => {
  if (typeof window === "undefined") return false;
  // EyeDropper is restricted to secure contexts. Localhost is considered
  // secure by browsers, while an insecure HTTP origin must stay disabled.
  return (
    window.isSecureContext !== false &&
    typeof window.EyeDropper === "function"
  );
});

function onUpdateModelValue(value) {
  resetText(value);
  emit("update:modelValue", value);
}

function onChange(value) {
  resetText(value);
  emit("active-change", value);
  emit("change", value);
}

function onActiveChange(value) {
  // A delayed panel reset must not overwrite unfinished manual input.
  if (textFocused.value && textDirty.value) return;
  resetText(value);
  emit("active-change", value);
}

async function pickFromScreen() {
  if (!eyeDropperSupported.value || props.disabled || picking.value) return;

  picking.value = true;
  const revision = modelRevision;
  const currentColor = props.modelValue || "";
  const controller = new AbortController();
  eyeDropperController = controller;
  try {
    const EyeDropper = window.EyeDropper;
    const eyeDropper = new EyeDropper();
    const result = await eyeDropper.open({ signal: controller.signal });
    const color = result?.sRGBHex;
    if (!disposed && !props.disabled && !controller.signal.aborted && revision === modelRevision &&
        typeof color === "string" && /^#[\da-f]{6}$/i.test(color)) {
      commitColor(preserveAlpha(color, currentColor));
    }
  } catch {
    // User cancellation and unavailable permissions are intentionally quiet.
  } finally {
    if (eyeDropperController === controller) eyeDropperController = undefined;
    picking.value = false;
  }
}
</script>

<style scoped>
.designer-color-picker {
  --designer-color-control-size: var(--dashboard-inspector-control-height, var(--el-component-size, 32px));
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  width: 100%;
  min-width: 0;
  line-height: 1;
  color: var(--el-text-color-regular);
}

.designer-color-picker__value {
  flex: 1 1 0;
  width: 0;
  min-width: 0;
  height: var(--designer-color-control-size);
}

.designer-color-picker__value :deep(.el-input__wrapper) {
  min-width: 0;
  height: 100%;
  box-sizing: border-box;
  padding: 1px 7px;
}

.designer-color-picker__value :deep(.el-input__inner) {
  min-width: 0;
  height: 100%;
  line-height: normal;
}

.designer-color-picker :deep(.el-color-picker) {
  display: flex;
  align-items: center;
  flex: 0 0 var(--designer-color-control-size);
  width: var(--designer-color-control-size);
  height: var(--designer-color-control-size);
  line-height: 1;
}

.designer-color-picker :deep(.el-color-picker__trigger) {
  display: flex;
  width: 100%;
  height: 100%;
}

.designer-color-picker__eyedropper {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  width: var(--designer-color-control-size);
  height: var(--designer-color-control-size);
  line-height: 1;
  padding: 0;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-secondary);
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s, background-color 0.2s;
}

.designer-color-picker__eyedropper:hover:not(:disabled) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.designer-color-picker__eyedropper:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.designer-color-picker__eyedropper:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.designer-color-picker__eyedropper .is-loading {
  animation: designer-color-picker-spin 1s linear infinite;
}

@keyframes designer-color-picker-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
