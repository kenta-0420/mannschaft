import type { ShiftSlotResponse } from '~/types/shift'

/**
 * F03.5 シフト枠ユーティリティ。
 */

/**
 * 枠が人員不足かどうかを判定する（CMP-260826-2127 / AC-4(4)）。
 *
 * サーバーが割当を伏せた枠（`assignmentMasked=true`）は `assignedUserIds` が空配列で返るため、
 * そのまま数えると全枠が「0/N」の人員不足に見えてしまう。伏せられている間は
 * 充足の判定自体を行わない。
 *
 * 判定に `schedule.status` を使わないのは、BE と FE で規則が二重化するのを避けるためである。
 * 同じ規則をシフト詳細（`/shift/{id}`）と枠編集（`/shift/{id}/edit`）の双方が使うので、
 * ここに一本化している（片方だけ直して片方が赤く光る事故を防ぐ）。
 *
 * @param slot シフト枠
 * @returns 人員不足なら true。割当が伏せられている間は常に false
 */
export function isSlotUnderStaffed(slot: ShiftSlotResponse): boolean {
  if (slot.assignmentMasked) return false
  return slot.assignedUserIds.length < slot.position.requiredCount
}
