package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * シフト割当<b>履歴</b>リポジトリ。
 *
 * <p><b>この表は現在の割当状態の正本ではない（CMP-260908-2117）。</b>
 * 現在誰がどの枠に入っているかは {@code shift_slots.assigned_user_ids} が正本であり、
 * 本表は「誰がいつどの戦略で割り当て・解除したか」を残す監査・履歴用である
 *（設計 {@code docs/features/F03.5_shift/01_db_design.md}）。</p>
 *
 * <p>かつて「自分のシフト」「今後の予定」「充足サマリー」が本表の
 * {@code status = CONFIRMED} を現在状態として引いており、同表に書き込むのが
 * 自動割当だけだったため、手動割当が読み出し側にまったく現れなかった。
 * これらの読み出しは JSON 列側へ移したため、本表を状態問い合わせに使うクエリは
 * <b>意図的に置いていない</b>。追加する場合は正本の役割分担を崩さないか確認すること。</p>
 */
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignmentEntity, Long> {

    /**
     * 実行履歴IDに紐づく割当一覧を取得する。
     */
    List<ShiftAssignmentEntity> findAllByRunId(Long runId);

    /**
     * スロットIDに紐づく割当一覧を取得する。
     */
    List<ShiftAssignmentEntity> findAllBySlotId(Long slotId);

}
