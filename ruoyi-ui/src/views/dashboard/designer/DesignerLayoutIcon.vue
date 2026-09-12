<template>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path :d="paths[name] || paths.left" />
  </svg>
</template>

<script setup>
defineProps({ name: { type: String, default: 'left' } })

const paths = {
  group: 'M3 3h18v18H3zM7 7h6v6H7zM11 11h6v6h-6z',
  ungroup: 'M3 3h7v7H3zM14 14h7v7h-7zM14 4h6v6m0-6-6 6M10 20H4v-6m0 6 6-6',
  select: 'M7 3H3v4m14-4h4v4M3 17v4h4m14-4v4h-4M7 7h6v6H7zM11 11h6v6h-6',
  left: 'M4 3v18M8 6h12v4H8zM8 14h8v4H8z',
  center: 'M12 3v18M4 6h16v4H4zM7 14h10v4H7z',
  right: 'M20 3v18M4 6h12v4H4zM8 14h8v4H8z',
  top: 'M3 4h18M6 8h4v12H6zM14 8h4v8h-4z',
  middle: 'M3 12h18M6 4h4v16H6zM14 7h4v10h-4z',
  bottom: 'M3 20h18M6 4h4v12H6zM14 8h4v8h-4z',
  'distribute-x': 'M2 3v18M22 3v18M5 7h3v10H5zM10.5 5h3v14h-3zM16 7h3v10h-3z',
  'distribute-y': 'M3 2h18M3 22h18M7 5h10v3H7zM5 10.5h14v3H5zM7 16h10v3H7z',
}
</script>
