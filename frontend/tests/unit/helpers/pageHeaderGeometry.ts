export interface HeaderRect { left: number; right: number; top: number; bottom: number; width: number; height: number }

/** items-endでも、全itemの縦区間が重なれば折り返しのない一行。操作群も独立して判定する。 */
export function isOneRow(items: HeaderRect[]): boolean {
  return items.length === 0 || Math.max(...items.map(item => item.top)) < Math.min(...items.map(item => item.bottom)) + 0.5
}
export function headerGroupsAreOneRow(titleItems: HeaderRect[], actionItems: HeaderRect[]): boolean {
  return titleItems.length > 0 && isOneRow(titleItems) && isOneRow(actionItems)
}
export function rectFits(child: HeaderRect, parent: HeaderRect, viewportWidth: number): boolean {
  return child.left >= parent.left - 1 && child.right <= parent.right + 1 && child.top >= parent.top - 1 && child.bottom <= parent.bottom + 1 && child.left >= -1 && child.right <= viewportWidth + 1
}
