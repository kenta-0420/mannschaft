import { useToast } from 'primevue/usetoast'

export default defineNuxtPlugin(() => {
  // useToast は setup コンテキスト外では動かないため、
  // $toast として提供し useApi 等から参照可能にする
  return {
    provide: {
      toast: useToast(),
    },
  }
})
