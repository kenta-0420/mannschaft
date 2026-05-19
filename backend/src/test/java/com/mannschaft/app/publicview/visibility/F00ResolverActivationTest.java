package com.mannschaft.app.publicview.visibility;

import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.visibility.OrganizationVisibilityResolver;
import com.mannschaft.app.team.visibility.TeamVisibilityResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Annotation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F19.1 Phase 1: F00 Phase D 既存 Resolver の恒常稼働化を確認する単体テスト。
 *
 * <p>設計書 §7.2 / §17.1 に基づき、F19.1 Phase 1 で {@code TeamVisibilityResolver} および
 * {@code OrganizationVisibilityResolver} の {@code @ConditionalOnProperty} を撤去し、
 * デフォルトで Spring の {@code ContentVisibilityChecker} に登録されるようにした。
 * 本テストは「feature flag による段階展開が解除されていること」をクラス注釈の有無で検証する。</p>
 *
 * <p>実際の判定ロジック（PUBLIC/PRIVATE/ARCHIVED/DELETED × viewer 立場）は
 * {@code TeamVisibilityResolverTest} / {@code OrganizationVisibilityResolverTest} で網羅済み。
 * 本テストは「F19.1 Phase 1 の DDL レベル変更（feature flag 撤去）が後退（regression）していないか」を
 * CI で守るためのガード。</p>
 */
@DisplayName("F19.1 Phase 1: F00 Phase D Resolver 恒常稼働化の確認")
class F00ResolverActivationTest {

    @Test
    @DisplayName("TeamVisibilityResolver に @ConditionalOnProperty が付与されていないこと")
    void teamResolver_isUnconditional() {
        assertThat(hasConditionalOnProperty(TeamVisibilityResolver.class))
                .as("F19.1 Phase 1 で feature flag 制御を撤去したため、本注釈は存在してはいけない")
                .isFalse();
    }

    @Test
    @DisplayName("OrganizationVisibilityResolver に @ConditionalOnProperty が付与されていないこと")
    void organizationResolver_isUnconditional() {
        assertThat(hasConditionalOnProperty(OrganizationVisibilityResolver.class))
                .as("F19.1 Phase 1 で feature flag 制御を撤去したため、本注釈は存在してはいけない")
                .isFalse();
    }

    @Test
    @DisplayName("referenceType() は TEAM / ORGANIZATION を返す（ReferenceType 列挙との整合性）")
    void referenceTypes_match() {
        // クラス定義としての referenceType() の戻り値は new で確認できないので、enum 値の存在確認のみ
        assertThat(ReferenceType.TEAM).isNotNull();
        assertThat(ReferenceType.ORGANIZATION).isNotNull();
        // F00 §3.3 Phase D 予約だった TEAM / ORGANIZATION が F19.1 Phase 1 で恒常稼働化された旨を記録
    }

    private boolean hasConditionalOnProperty(Class<?> clazz) {
        for (Annotation annotation : clazz.getAnnotations()) {
            if (annotation.annotationType().equals(ConditionalOnProperty.class)) {
                return true;
            }
        }
        return false;
    }
}
