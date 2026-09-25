package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練（AC-31）。
 *
 * <p>軍議第8版確定稿 §3.1・§4「テンプレートと宛先グループ」AC-31（グループの作成・一覧・
 * 名前重複409）を対象とする。{@link ConfirmableRecipientGroupService} は実データベースへ
 * 永続化するため、Testcontainers の MySQL を使う IT として実データを検証する
 * （是正: コンストラクタ引数レベルの契約確認のみに留めていた軽量ユニットテストから昇格）。</p>
 *
 * <p>CI是正（CMP-260920-1040）: {@code ConfirmableTargetAuthorizationValidator} は
 * AC-35（クエリ数を件数非依存にする）のため実データベースの組織ツリーを再帰CTEで検証する
 * （{@code findOrganizationTreeIds}）。存在しない組織ID（架空の100L等）を渡すとツリーが
 * 空集合になり、常に {@code TARGET_OUT_OF_SCOPE} になる。検証器の実装は正しいため、
 * フィクスチャ側を実在する組織で置き換える（対処療法で検証を緩めない）。</p>
 */
@DisplayName("ConfirmableRecipientGroupService 試練（AC-31）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ConfirmableRecipientGroupServiceTest extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private ConfirmableRecipientGroupService service;

    @Autowired
    private OrganizationRepository organizationRepository;

    private long createOrg() {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .slug("crg-it-" + SLUG_SEQ.incrementAndGet() + "-" + (System.nanoTime() % 1_000_000L))
                .name("confirmable recipient group IT org")
                .orgType(OrganizationEntity.OrgType.COMMUNITY)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.FULL)
                .supporterEnabled(Boolean.TRUE)
                .build());
        return org.getId();
    }

    private ConfirmableRecipientGroupCreateRequest buildRequest(String name, List<ConfirmableTargetSpec> targets) {
        try {
            ConfirmableRecipientGroupCreateRequest req = new ConfirmableRecipientGroupCreateRequest();
            Field nameField = ConfirmableRecipientGroupCreateRequest.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(req, name);
            Field targetsField = ConfirmableRecipientGroupCreateRequest.class.getDeclaredField("targets");
            targetsField.setAccessible(true);
            targetsField.set(req, targets);
            return req;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("AC-31: グループを作成できる（正常系）")
    void ac31_グループを作成できる() {
        long orgId = createOrg();
        ConfirmableRecipientGroupCreateRequest request = buildRequest(
                "配下全チーム-" + System.nanoTime(),
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, orgId)));

        ConfirmableRecipientGroupResponse response =
                service.create(ScopeType.ORGANIZATION, orgId, 1L, request);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getTargets()).hasSize(1);
    }

    @Test
    @DisplayName("AC-31: 同じスコープで名前が重複すると409 GROUP_NAME_DUPLICATEになる")
    void ac31_同名グループは409になる() {
        long orgId = createOrg();
        String name = "同名グループ-" + System.nanoTime();
        ConfirmableRecipientGroupCreateRequest request = buildRequest(
                name, List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, orgId)));

        service.create(ScopeType.ORGANIZATION, orgId, 1L, request);

        assertThatThrownBy(() -> service.create(ScopeType.ORGANIZATION, orgId, 1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.GROUP_NAME_DUPLICATE);
    }

    @Test
    @DisplayName("AC-31: 登録したグループの一覧が取得できる")
    void ac31_一覧取得() {
        long orgId = createOrg();
        String name = "一覧確認グループ-" + System.nanoTime();
        ConfirmableRecipientGroupCreateRequest request = buildRequest(
                name, List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, orgId)));
        service.create(ScopeType.ORGANIZATION, orgId, 1L, request);

        List<ConfirmableRecipientGroupResponse> groups = service.list(ScopeType.ORGANIZATION, orgId);

        assertThat(groups).extracting(ConfirmableRecipientGroupResponse::getName).contains(name);
    }
}
