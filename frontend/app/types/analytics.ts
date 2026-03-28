export interface PageViewStats {
  totalViews: number
  uniqueVisitors: number
  memberViews: number
  guestViews: number
}

export interface DailyPageView {
  date: string
  views: number
  uniqueVisitors: number
}

export interface MonthlyPageView {
  month: string
  views: number
  uniqueVisitors: number
}

export interface ContentRanking {
  contentType: string
  contentId: number
  title: string
  url: string
  views: number
  uniqueVisitors: number
}

export interface AnalyticsResponse {
  summary: PageViewStats
  daily: DailyPageView[]
  monthly: MonthlyPageView[]
  topContent: ContentRanking[]
}
