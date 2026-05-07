/**
 * frappe-gantt v1.2 用 最小型定義（F09.13 Phase 2-α-4）。
 *
 * 公式の @types パッケージは存在しないため、本プロジェクトで利用する
 * コンストラクタ／タスク／オプションのみを最小限に shim する。
 *
 * 公式リポジトリ: https://github.com/frappe/gantt
 */
declare module 'frappe-gantt' {
  export interface GanttTask {
    id: string
    name: string
    start: string | Date
    end: string | Date
    progress?: number
    dependencies?: string | string[]
    custom_class?: string
  }

  export type GanttViewMode =
    | 'Quarter Day'
    | 'Half Day'
    | 'Day'
    | 'Week'
    | 'Month'
    | 'Year'

  export interface GanttOptions {
    header_height?: number
    column_width?: number
    step?: number
    view_modes?: GanttViewMode[]
    bar_height?: number
    bar_corner_radius?: number
    arrow_curve?: number
    padding?: number
    view_mode?: GanttViewMode
    date_format?: string
    language?: string
    custom_popup_html?: ((task: GanttTask) => string) | null
    on_click?: (task: GanttTask) => void
    on_date_change?: (task: GanttTask, start: Date, end: Date) => void
    on_progress_change?: (task: GanttTask, progress: number) => void
    on_view_change?: (mode: GanttViewMode) => void
  }

  export default class Gantt {
    constructor(target: string | HTMLElement | SVGElement, tasks: GanttTask[], options?: GanttOptions)
    refresh(tasks: GanttTask[]): void
    change_view_mode(mode: GanttViewMode): void
  }
}

declare module 'frappe-gantt/dist/frappe-gantt.css'
