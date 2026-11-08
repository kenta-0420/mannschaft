import { defineNuxtPlugin } from '#app'
import { createRanchCommandMemory } from '~/composables/useRanchCommandMemory'

export default defineNuxtPlugin(nuxtApp => {
 const auth = useAuthStore()
 const memory = createRanchCommandMemory(() => auth.user?.id ?? null)
 // component remountでは破棄せず、アプリ終了時だけwatchを解除する。
 nuxtApp.vueApp.onUnmount(memory.dispose)
 return { provide: { ranchCommandMemory: memory } }
})