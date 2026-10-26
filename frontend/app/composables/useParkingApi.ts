// このファイルは後方互換のためのre-export専用です。
// 新規コードは parking/ サブディレクトリの各composableを直接インポートしてください。
export * from './parking/useParkingSpacesApi'
export * from './parking/useParkingApplicationsApi'
export * from './parking/useParkingListingsApi'
export * from './parking/useParkingSettingsApi'
export * from './parking/useParkingSubleaseApi'
export * from './parking/useParkingVisitorReservationsApi'
export * from './parking/useParkingVisitorRecurringApi'
export * from './parking/useParkingWatchlistApi'
export * from './parking/usePersonalVehiclesApi'

// useParkingApi() の後方互換ラッパー
// 既存の呼び出し元は変更不要です。
export function useParkingApi() {
  const spaces = useParkingSpacesApi()
  const applications = useParkingApplicationsApi()
  const listings = useParkingListingsApi()
  const settings = useParkingSettingsApi()
  const sublease = useParkingSubleaseApi()
  const visitorReservations = useParkingVisitorReservationsApi()
  const visitorRecurring = useParkingVisitorRecurringApi()
  const watchlist = useParkingWatchlistApi()
  const vehicles = usePersonalVehiclesApi()

  return {
    ...spaces,
    ...applications,
    ...listings,
    ...settings,
    ...sublease,
    ...visitorReservations,
    ...visitorRecurring,
    ...watchlist,
    ...vehicles,
  }
}
