package com.mannschaft.app.receipt;

import com.mannschaft.app.receipt.dto.PlatformReceiptIssueCommand;

import java.util.Optional;

/**
 * 収入源ドメインが実装する、運営領収書の発行内容の解決口（F08.12 §5.2）。
 *
 * <p>領収書の発行契機となるイベントは ID しか運ばない（クロスドメインのイベントに
 * エンティティを載せない。設計原則 1・5）。実際の宛名・金額・税額は、
 * <b>各収入源ドメイン自身</b>が自分のテーブルから引き直して組み立てる。</p>
 *
 * <p>この向きにすることで、receipt ドメインは advertising / notification といった
 * 他ドメインの Repository を一切知らずに済む。収入源が増えても receipt 側の改修は要らない。</p>
 */
public interface PlatformReceiptSourceResolver {

    /** この実装が担当する元データ種別。 */
    ReceiptSourceType supportedSourceType();

    /**
     * 元データから発行指示を組み立てる。
     *
     * @param sourceRef 元データ ID
     * @return 発行指示。元データが存在しない、または未入金なら空
     */
    Optional<PlatformReceiptIssueCommand> resolve(ReceiptSourceRef sourceRef);
}
