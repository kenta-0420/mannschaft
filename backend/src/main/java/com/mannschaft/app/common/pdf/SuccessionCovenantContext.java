package com.mannschaft.app.common.pdf;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入居時誓約 PDF テンプレート（{@code pdf/succession-covenant.html}）の描画コンテキスト。
 *
 * <p>F09.15 §7.1 入居時誓約フロー UC-A1 で利用。
 *
 * @param subjectId           署名対象識別子（succession_covenants.id を String 化したもの）
 * @param covenantType        誓約区分（SUCCESSION_PRE_REGISTRATION / PRIVACY_CONSENT / MONITORING_CONSENT）
 * @param covenantTypeLabel   誓約区分の表示用ラベル（例: "事前登録誓約"）
 * @param covenantVersion     誓約テンプレ版数（v1.0.0 固定）
 * @param residentName        居住者氏名
 * @param dwellingUnitLabel   居室番号表示（例: "301 号室"）
 * @param residentType        区分所有者属性（OWNER / RENTER 等）
 * @param contractDate        契約日・入居日
 * @param signedAt            署名日時
 * @param organizationName    管理組合（団体名）
 * @param representativeName  代表者表示（例: "理事長 鈴木 一郎"）
 */
public record SuccessionCovenantContext(
        String subjectId,
        String covenantType,
        String covenantTypeLabel,
        String covenantVersion,
        String residentName,
        String dwellingUnitLabel,
        String residentType,
        LocalDate contractDate,
        LocalDateTime signedAt,
        String organizationName,
        String representativeName
) {
}
