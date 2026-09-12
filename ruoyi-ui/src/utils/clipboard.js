// 与 RuoYi v-copyText 一致，先在点击调用栈内同步执行兼容复制。
// 不等 Clipboard Promise 拒绝后才执行 execCommand，以免丢失用户手势。
function captureSelection(document, window, input) {
  const focused = document.activeElement
  const selection = document.getSelection?.()
  const ranges = []
  for (let index = 0; index < (selection?.rangeCount || 0); index++) {
    try { ranges.push(selection.getRangeAt(index).cloneRange()) } catch { /* 已失效的范围不能恢复。 */ }
  }
  const textSelection = typeof focused?.selectionStart === 'number'
    ? [focused.selectionStart, focused.selectionEnd, focused.selectionDirection]
    : null
  const elements = new Set([document.scrollingElement, document.documentElement, document.body])
  for (const start of [focused, input]) {
    for (let node = start; node; node = node.parentElement) elements.add(node)
  }
  const scroll = [...elements].filter(Boolean).map(element => [element, element.scrollLeft, element.scrollTop])
  const windowLeft = window?.scrollX || 0
  const windowTop = window?.scrollY || 0
  const restoreScroll = () => {
    scroll.forEach(([element, left, top]) => { element.scrollLeft = left; element.scrollTop = top })
    if (window && (window.scrollX !== windowLeft || window.scrollY !== windowTop)) window.scrollTo?.(windowLeft, windowTop)
  }
  return {
    restoreScroll,
    restore() {
      if (focused?.isConnected !== false) {
        try { focused?.focus?.({ preventScroll: true }) } catch { try { focused?.focus?.() } catch { /* 控件可能已移除。 */ } }
        if (textSelection) {
          try { focused.setSelectionRange(...textSelection) } catch { /* 非文本控件没有选区。 */ }
        }
      }
      if (selection) {
        try {
          selection.removeAllRanges()
          ranges.forEach(range => selection.addRange(range))
        } catch { /* 复制期间失效的 DOM 选区不能恢复。 */ }
      }
      restoreScroll()
    },
  }
}

function copySynchronously(text, document, window, input) {
  if (typeof document?.execCommand !== 'function' || !document.body) return false
  const state = captureSelection(document, window, input)
  let element
  try {
    element = document.createElement('textarea')
    element.value = text
    element.setAttribute('readonly', '')
    element.setAttribute('aria-hidden', 'true')
    element.tabIndex = -1
    Object.assign(element.style, { position: 'fixed', top: '0', left: '0', width: '1px', height: '1px', padding: '0', border: '0', opacity: '0', fontSize: '16px' })
    // 放进当前对话框，避免焦点陷阱把选区从临时文本框移走。
    const container = input?.closest?.('[role="dialog"]') || document.body
    container.appendChild(element)
    element.select()
    element.setSelectionRange(0, text.length)
    return document.execCommand('copy') === true
  } catch {
    return false
  } finally {
    element?.remove()
    state.restore()
  }
}

function selectVisibleText(text, input, document, window) {
  if (!input || input.isConnected === false || String(input.value) !== text) return false
  const state = captureSelection(document, window, input)
  try {
    try { input.focus({ preventScroll: true }) } catch { input.focus() }
    input.select()
    input.setSelectionRange(0, text.length)
    return true
  } catch {
    return false
  } finally {
    // 手动复制需要留下链接选区，只恢复滚动位置。
    state.restoreScroll()
  }
}

export function copyTextWithFallback(value, options = {}) {
  const text = String(value ?? '')
  const document = options.document || globalThis.document
  const window = options.window || document?.defaultView || globalThis.window
  const navigator = options.navigator || globalThis.navigator
  const input = options.input
  const failed = () => ({ copied: false, selected: selectVisibleText(text, input, document, window) })
  if (!text || !document) return Promise.resolve({ copied: false, selected: false })
  if (copySynchronously(text, document, window, input)) return Promise.resolve({ copied: true, selected: false })
  try {
    const result = navigator?.clipboard?.writeText?.(text)
    if (result?.then) return Promise.resolve(result).then(() => ({ copied: true, selected: false }), failed)
  } catch { /* 缺失、同步拒绝时保留手动复制入口。 */ }
  return Promise.resolve(failed())
}
