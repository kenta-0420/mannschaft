package com.mannschaft.app.publicview.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F19.1 §10.4 Defense in Depth: 公開 DTO の禁則フィールド検出テスト。
 *
 * <p>公開 API のレスポンス DTO に PII（個人識別情報）/ 内部状態が混入していないかを
 * リフレクションで検出する CI 自動テスト。DTO に新フィールドを追加する際、
 * 誤って禁則ワードを含むフィールドを追加した場合に本テストで検知できる。</p>
 *
 * <p>各 DTO のフィールド名（record component name）を {@link #FORBIDDEN_FIELDS} と
 * 突き合わせ、含まれていないことをアサートする。</p>
 */
@DisplayName("公開 DTO の PII / 内部状態 漏洩防止 (F19.1 §10.4)")
class PublicDtoLeakageTest {

    /** 公開 DTO に絶対に含めてはならないフィールド名。 */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            // 個人情報
            "firstName", "lastName", "lastNameKana", "firstNameKana",
            "email", "phone", "phoneNumber", "birthday",
            "passwordHash", "refreshToken",
            "address", "addressLine", "streetAddress", "postalCode",
            // 投稿者の生 ID（{@link PublicAuthorIdentity} で隠蔽済みのため除外）
            "userId", "authorId", "ownerId",
            // 本名スナップショット（Phase 2 で導入予定だが DTO レベルでは封じる）
            "realNameSnapshot", "authorRealNameSnapshot",
            // 内部状態
            "version", "archivedAt", "deletedAt",
            "rejectionReason", "previewToken", "previewTokenExpiresAt",
            "members", "memberList", "userList", "memberRoster", "userRoster",
            "supporterEnabled", "profileVisibility", "parentOrganizationId"
    );

    /** 検査対象の公開 DTO クラス一覧。 */
    private static final List<Class<?>> PUBLIC_DTO_CLASSES = List.of(
            PublicTeamResponse.class,
            PublicOrganizationResponse.class,
            PublicPostSummary.class,
            PublicPostDetail.class,
            PublicAuthorIdentity.class,
            PublicScopeRef.class
    );

    @Test
    @DisplayName("全 Public DTO のフィールド名に禁則ワードが含まれないこと")
    void publicDtos_doNotExposeForbiddenFields() {
        for (Class<?> dtoClass : PUBLIC_DTO_CLASSES) {
            assertThat(dtoClass.isRecord())
                    .as("Public DTO は record で実装すること (PII 防御の最良の選択): %s",
                            dtoClass.getName())
                    .isTrue();

            List<String> componentNames = Arrays.stream(dtoClass.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toList());

            for (String fieldName : componentNames) {
                assertThat(FORBIDDEN_FIELDS)
                        .as("Public DTO %s に禁則フィールド '%s' が含まれている (§10.4 Defense in Depth)",
                                dtoClass.getSimpleName(), fieldName)
                        .doesNotContain(fieldName);
            }
        }
    }
}
