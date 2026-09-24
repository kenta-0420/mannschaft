package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientPreviewRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientPreviewResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練（AC-20・AC-35）。
 *
 * <p>軍議第8版確定稿 §3.3・§4「件数・非同期」AC-20、「性能」AC-35 を対象とする。
 * 骨格段階では {@link ConfirmableRecipientPreviewService} はスタブのため red。</p>
 *
 * <p>AC-35（認可検証はターゲット件数Nに比例したクエリ数にしない）は、実クエリ回数の計測に
 * Testcontainers + datasource-proxy か Hibernate 統計が必要なため、骨格・試練Aでは未着手とする
 * （§12 の趣旨と同様の計測手段が要る。出陣担当・ワーカー隊への申し送り事項）。</p>
 */
@DisplayName("ConfirmableRecipientPreviewService 試練（AC-20）")
class ConfirmableRecipientPreviewServiceTest {

    private final ConfirmableRecipientPreviewService service = new ConfirmableRecipientPreviewService();

    @Test
    @DisplayName("AC-20: 見込みが0件になる宛先を渡すとestimatedRecipientCountが0で返る（例外にしない）")
    void ac20_見込み0件_0件で返る() {
        ConfirmableRecipientPreviewRequest request = new ConfirmableRecipientPreviewRequest();

        // 出陣後は 0 件の見込みで正常応答するはず（409 を投げるのは「送信 API」側の責務であり、
        // プレビュー自体は例外にしない設計。骨格段階は UnsupportedOperationException のため red）。
        ConfirmableRecipientPreviewResponse response =
                service.preview(ScopeType.ORGANIZATION, 999L, 1L, request);

        assertThat(response.getEstimatedRecipientCount()).isZero();
    }
}
