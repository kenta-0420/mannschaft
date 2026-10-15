package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自動引用ソース: {@code dwelling_unit.owner} — 所有者氏名。
 *
 * <p>設計書 F09.14 §5.2 表中「所有者氏名（個人情報配慮、ADMIN承認後のみ展開）」および
 * §6.2「個人情報の取扱い」に対応。{@link AutoFillContext#allowPersonalInfo()} が
 * {@code false} の場合は問答無用で {@code null} を返し、
 * 設計書 §6.2 で求められる「許諾なしでは空欄出力」を保証する。</p>
 *
 * <p><b>Phase 2 暫定実装</b>: 実装本体（resident_registry からの所有者氏名取得）は
 * Phase 2-γ（API 実装）でサービス間配線時に書き入れる。本クラスは現時点では
 * 個人情報フラグの判定とホワイトリストへの登録のみを担う。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DwellingUnitOwnerSource implements AutoFillSource {

    @Override
    public String key() {
        return "dwelling_unit.owner";
    }

    @Override
    public Object resolve(AutoFillContext context) {
        if (!context.allowPersonalInfo()) {
            // 設計書 §6.2: 許諾なしでは空欄出力（手動入力必須）
            return null;
        }
        if (context.targetDwellingUnitId() == null) {
            return null;
        }
        // Phase 2-β-2 では resident_registry 連携の配線まで担当範囲外。
        // Phase 2-γ で実装される ResidentRegistryRepository から所有者氏名を取得する。
        log.debug("dwelling_unit.owner is not yet implemented (Phase 2-γ で配線予定)");
        return null;
    }
}
