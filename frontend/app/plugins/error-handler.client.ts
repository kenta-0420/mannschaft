export default defineNuxtPlugin((nuxtApp) => {
  const errorReport = useErrorReport()

  // Vue component errors (sync & async)
  nuxtApp.vueApp.config.errorHandler = (error, _instance, info) => {
    errorReport.capture(error, { context: info })
  }

  // Unhandled promise rejections
  window.addEventListener('unhandledrejection', (event) => {
    errorReport.capture(event.reason)
  })
})
