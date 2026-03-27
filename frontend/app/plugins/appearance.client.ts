export default defineNuxtPlugin(() => {
  const appearanceStore = useAppearanceStore()
  appearanceStore.loadFromStorage()
})
