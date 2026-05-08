package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自動引用ソース: {@code parking.assignment_status} — 利用権形態（占有/共用）。
 *
 * <p>設計書 F09.14 §5.2 表中「利用権形態（占有/共用）」に対応。</p>
 *
 * <p><b>Phase 2-β-2 暫定実装</b>: parking_assignments テーブルから利用権形態を集計するロジックは
 * Phase 2-γ で配線する。現時点ではホワイトリストキーの予約のみを担当し、
 * 値は {@code null}（未集計）を返す。設計書 §5.2 の集計仕様確定後に
 * {@code ParkingAssignmentRepository} と接続する。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParkingAssignmentStatusSource implements AutoFillSource {

    @Override
    public String key() {
        return "parking.assignment_status";
    }

    @Override
    public Object resolve(AutoFillContext context) {
        log.debug("parking.assignment_status is not yet implemented (Phase 2-γ で配線予定)");
        return null;
    }
}
