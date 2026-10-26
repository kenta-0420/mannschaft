package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;

/**
 * テンプレ枠生成が<b>1 つ以上の日付チャンクをコミットした後で</b>失敗したことを表す例外
 * （F03.4.5 §3.1 契約: 「{@code generation.failed=true} のときカウント各値はコミット済みチャンク分」）。
 *
 * <p>生成は日付単位のチャンク tx（{@code REQUIRES_NEW}）で行うため、途中で失敗しても先行チャンクは
 * 既に永続化済みである。この例外は<b>コミット済み分のカウント</b>を {@link #getAccumulated()} で保持し、
 * 保存フロー（テンプレ POST/PATCH・営業時間 PUT）のコントローラがトーストに実件数を載せられるようにする
 * （0 件で報告すると「保存し 0 件更新」という嘘になり症状の黙殺＝根治原則違反になる）。</p>
 *
 * <p>1 チャンクもコミットする前の失敗（テンプレ/営業時間クエリ・先読み段の失敗）はこの例外にはならず、
 * 素の例外として伝播する（真に 0 件なのでコントローラは全カウント 0 の失敗として報告する）。</p>
 */
public class SlotGenerationPartialException extends RuntimeException {

    /** コミット済みチャンクぶんの生成結果カウント（horizon 情報つき）。 */
    private final transient GenerateSlotsResponse accumulated;

    public SlotGenerationPartialException(GenerateSlotsResponse accumulated, Throwable cause) {
        super("テンプレ枠生成が部分的にコミットされた後に失敗しました（コミット済み分は永続化済み・翌日次バッチが残りを自己修復）",
                cause);
        this.accumulated = accumulated;
    }

    /** コミット済みチャンクぶんの生成結果カウント（horizon 情報つき）を返す。 */
    public GenerateSlotsResponse getAccumulated() {
        return accumulated;
    }
}
