package com.mannschaft.app.village.entity;

import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>F17.1 ②-1 改ざん不可（snapshot 凍結）番人</b>（設計書 §4.2 / AC-02）。
 *
 * <p>凍結ダイジェスト {@code digest_*} は号の凍結後に書き換え不可でなければならない。本テストは
 * {@link VillageNewsletterIssueEntity} が <b>digest_* への setter／ミューテータ経路を一切持たない</b>
 * こと（＝「更新経路が存在しない」＝ AC-02）と、二重凍結が拒否されること、コメントは凍結後も
 * 編集できることを検証する。DB を要さない純ドメイン UT。</p>
 */
@DisplayName("村ニュースレター号 改ざん不可（snapshot 凍結）番人")
class VillageNewsletterIssueEntityImmutabilityTest {

    @Test
    @DisplayName("AC-02 digest_* フィールドに public な setter／ミューテータが存在しない")
    void ダイジェスト列に更新経路が存在しない() {
        // Lombok @Setter を付けていれば setDigestPostCount 等が生成される。存在しないことを機械的に確認する。
        var mutators = Arrays.stream(VillageNewsletterIssueEntity.class.getMethods())
                .map(Method::getName)
                .filter(n -> n.toLowerCase().contains("digest"))
                .filter(n -> !n.startsWith("get"))  // getter は許可
                .toList();

        assertThat(mutators)
                .as("digest_* を書き換える public メソッド（setDigest*/withDigest* 等）が存在しないこと。"
                        + "存在すると凍結後の集計値を改ざんできてしまう（設計書 §4.2）")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-02 二重凍結は IllegalStateException で拒否される（凍結値の再確定を許さない）")
    void 二重凍結は拒否される() {
        VillageNewsletterIssueEntity issue = VillageNewsletterIssueEntity.builder()
                .status(VillageNewsletterIssueStatus.AGGREGATED)
                .digestPostCount(10)
                .build();

        LocalDateTime now = LocalDateTime.of(2026, 7, 17, 3, 0);
        issue.freeze(now, now.plusDays(4));
        assertThat(issue.getStatus()).isEqualTo(VillageNewsletterIssueStatus.FROZEN);
        assertThat(issue.getDigestPostCount()).isEqualTo(10);

        assertThatThrownBy(() -> issue.freeze(now, now.plusDays(4)))
                .as("凍結済みの号を再凍結できないこと（改ざん防止）")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC-09 コメントは凍結後も編集できる（snapshot 不変性の対象外）")
    void コメントは凍結後も編集できる() {
        VillageNewsletterIssueEntity issue = VillageNewsletterIssueEntity.builder()
                .status(VillageNewsletterIssueStatus.AGGREGATED)
                .digestPostCount(5)
                .build();
        LocalDateTime now = LocalDateTime.of(2026, 7, 17, 3, 0);
        issue.freeze(now, now.plusDays(4));

        issue.updateComment("村長です。今週もお疲れさまでした。", 42L, now.plusDays(1));

        assertThat(issue.getHeadmanComment()).isEqualTo("村長です。今週もお疲れさまでした。");
        assertThat(issue.getCommentUpdatedBy()).isEqualTo(42L);
        // ダイジェスト値はコメント編集の影響を受けない
        assertThat(issue.getDigestPostCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("公開範囲は VILLAGE_MEMBERS↔PUBLIC を切り替えられる")
    void 公開範囲を切り替えられる() {
        VillageNewsletterIssueEntity issue = VillageNewsletterIssueEntity.builder()
                .visibility(VillageNewsletterVisibility.VILLAGE_MEMBERS)
                .build();
        issue.changeVisibility(VillageNewsletterVisibility.PUBLIC);
        assertThat(issue.getVisibility()).isEqualTo(VillageNewsletterVisibility.PUBLIC);
    }
}
