package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientPreviewRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientPreviewResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練（AC-20・AC-35）。
 *
 * <p>軍議第8版確定稿 §3.3・§4「件数・非同期」AC-20 を対象とする。{@link ConfirmableRecipientPreviewService}
 * は実データベースを引いて件数を数えるため、Testcontainers の MySQL を使う IT として検証する
 * （是正: 無引数コンストラクタでの DB 非依存スタブ確認から昇格）。</p>
 *
 * <p>AC-35（認可検証はターゲット件数Nに比例したクエリ数にしない）は、実クエリ回数の計測に
 * datasource-proxy 等の追加計装が要るため、本クラスでは未着手とする（ワーカー隊 §12 の
 * 計測手段が整い次第、別クラスで固定することが望ましい）。</p>
 */
@DisplayName("ConfirmableRecipientPreviewService 試練（AC-20）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ConfirmableRecipientPreviewServiceTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ConfirmableRecipientPreviewService service;

    @Test
    @DisplayName("AC-20: 見込みが0件になる宛先を渡すとestimatedRecipientCountが0で返る（例外にしない）")
    void ac20_見込み0件_0件で返る() {
        // 存在しない組織ID配下は0人（メンバーもチームも無い）と見込まれる。
        ConfirmableRecipientPreviewRequest request = new ConfirmableRecipientPreviewRequest();

        ConfirmableRecipientPreviewResponse response =
                service.preview(ScopeType.ORGANIZATION, 999_999_999L, 1L, request);

        assertThat(response.getEstimatedRecipientCount()).isZero();
    }
}
