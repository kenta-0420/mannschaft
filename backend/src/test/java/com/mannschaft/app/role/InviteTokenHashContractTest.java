package com.mannschaft.app.role;

import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.common.token.PlaintextTokenDebt;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.dto.InviteCardData;
import com.mannschaft.app.role.dto.MembershipInviteIssuedToken;
import com.mannschaft.app.role.dto.MembershipInviteResponse;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.service.MembershipInviteTokenIssuer;
import com.mannschaft.app.role.service.SharedInviteTokenIssuer;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練（CMP-260826-1457 W1 P1・系統1 role の招待トークン）: 型の契約（Spring・Docker 不要）。
 *
 * <p>陣立て書 §2-2 の 1〜4・6・9 と AC-1・AC-2・AC-3・AC-4・AC-12・AC-27 のうち、クラスの形で決まるものを
 * リフレクションで固定する。新しい型の名前はコンパイルに依存させないため {@link Class#forName(String)} で引き、
 * 無いときは assertion で落とす（コンパイル エラーにしない）。</p>
 *
 * <p>red の理由: いまの main は平文列 {@code token}・{@code findByToken}・平文を返す DTO・債務注釈が残っている。</p>
 */
@DisplayName("CMP-260826-1457 W1 P1: role 招待トークンのハッシュ保存の型契約")
class InviteTokenHashContractTest {

    private static final String DEBT_CMP = "CMP-260826-1457";

    @Nested
    @DisplayName("AC-1: Entity は平文を持たず token_hash だけを持つ")
    class EntityShape {

        @Test
        @DisplayName("InviteTokenEntity に平文の token フィールドが無い")
        void 平文フィールドが無い() {
            assertThat(fieldNames(InviteTokenEntity.class)).doesNotContain("token");
        }

        @Test
        @DisplayName("InviteTokenEntity.tokenHash は token_hash 列（64 字・一意・NOT NULL）に写る")
        void tokenHash列がある() {
            Optional<Field> tokenHash = findField(InviteTokenEntity.class, "tokenHash");
            assertThat(tokenHash).as("InviteTokenEntity.tokenHash が無い").isPresent();
            Column column = tokenHash.orElseThrow().getAnnotation(Column.class);
            assertThat(column).as("@Column が無い").isNotNull();
            assertThat(column.name()).isEqualTo("token_hash");
            assertThat(column.length()).isEqualTo(64);
            assertThat(column.unique()).isTrue();
            assertThat(column.nullable()).isFalse();
            assertThat(tokenHash.orElseThrow().getType()).isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("§2-2 の 2: Repository は平文で検索しない")
    class RepositoryShape {

        @Test
        @DisplayName("findByToken・findByTokenForUpdate が無い")
        void 平文検索メソッドが無い() {
            assertThat(methodNames(InviteTokenRepository.class))
                    .doesNotContain("findByToken", "findByTokenForUpdate");
        }

        @Test
        @DisplayName("findByTokenHash(String)・findByTokenHashForUpdate(String) がある")
        void ハッシュ検索メソッドがある() {
            assertThat(hasMethod(InviteTokenRepository.class, "findByTokenHash", String.class))
                    .as("InviteTokenRepository.findByTokenHash(String)").isTrue();
            assertThat(hasMethod(InviteTokenRepository.class, "findByTokenHashForUpdate", String.class))
                    .as("InviteTokenRepository.findByTokenHashForUpdate(String)").isTrue();
        }
    }

    @Nested
    @DisplayName("AC-2: 発行は SecretTokenVault を通す")
    class IssuerUsesVault {

        @Test
        @DisplayName("共有招待・承諾型招待の発行者が SecretTokenVault を依存に持つ")
        void 発行者が金庫を持つ() {
            for (Class<?> issuer : List.of(SharedInviteTokenIssuer.class, MembershipInviteTokenIssuer.class)) {
                assertThat(Arrays.stream(issuer.getDeclaredFields()).map(Field::getType).toList())
                        .as("%s が SecretTokenVault を注入していない", issuer.getSimpleName())
                        .contains(SecretTokenVault.class);
            }
        }
    }

    @Nested
    @DisplayName("AC-3・AC-4: 発行応答と一覧応答の DTO を分ける")
    class ResponseSplit {

        @Test
        @DisplayName("発行応答 InviteTokenIssuedResponse は平文 token を持ち、JSON は id・token・roleName 等を平らに返す（形は変わらない）")
        void 発行応答は平文を持つ() throws Exception {
            Optional<Class<?>> issued = loadClass("com.mannschaft.app.role.dto.InviteTokenIssuedResponse");
            assertThat(issued).as("発行応答 DTO InviteTokenIssuedResponse が無い").isPresent();
            // 日時のフィールドは一覧と同じ InviteTokenResponse だけが持ち、発行応答は平文 token と合成するだけ。
            assertThat(fieldNames(issued.orElseThrow())).containsExactlyInAnyOrder("token", "invite");

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            com.fasterxml.jackson.databind.JsonNode json = mapper.valueToTree(new com.mannschaft.app.role.dto.InviteTokenIssuedResponse(
                    "plain", new com.mannschaft.app.role.dto.InviteTokenResponse(1L, "MEMBER", null, null, 0, null, null)));
            java.util.Set<String> keys = new java.util.TreeSet<>();
            json.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactlyInAnyOrder("id", "token", "roleName", "expiresAt", "maxUses",
                    "usedCount", "revokedAt", "createdAt");
            assertThat(json.get("token").asText()).isEqualTo("plain");
        }

        @Test
        @DisplayName("一覧応答 InviteTokenResponse は平文を持たない（許可フィールドの完全一致）")
        void 一覧応答は平文を持たない() {
            Optional<Class<?>> list = loadClass("com.mannschaft.app.role.dto.InviteTokenResponse");
            assertThat(list).as("一覧応答 DTO InviteTokenResponse が無い").isPresent();
            assertThat(fieldNames(list.orElseThrow()))
                    .containsExactlyInAnyOrder("id", "roleName", "expiresAt", "maxUses",
                            "usedCount", "revokedAt", "createdAt");
        }
    }

    @Nested
    @DisplayName("AC-12: 宛先指定型は平文をどこにも載せない（ID で承諾・辞退する）")
    class TargetedInviteCarriesNoPlaintext {

        @Test
        @DisplayName("招待カードの描画契約（role→chat）に token が無く tokenId がある")
        void 招待カード契約にtokenが無い() {
            assertThat(fieldNames(InviteCardData.class)).contains("tokenId").doesNotContain("token");
        }

        @Test
        @DisplayName("チャットの MessageResponse.InviteCardDto に token が無い")
        void チャットのカードDTOにtokenが無い() {
            assertThat(fieldNames(MessageResponse.InviteCardDto.class)).contains("tokenId").doesNotContain("token");
        }

        @Test
        @DisplayName("承諾型招待の発行応答（発行者へ返す）に token が無い")
        void 発行応答にtokenが無い() {
            assertThat(fieldNames(MembershipInviteResponse.class)).contains("tokenId").doesNotContain("token");
        }

        @Test
        @DisplayName("承諾型招待の発行結果（role 内部の受け渡し）にも token が無い")
        void 発行結果にtokenが無い() {
            assertThat(fieldNames(MembershipInviteIssuedToken.class)).contains("id").doesNotContain("token");
        }
    }

    @Nested
    @DisplayName("AC-27: P1 は系統1の債務注釈を外す（足さない）")
    class DebtRemoved {

        @Test
        @DisplayName("InviteTokenEntity のフィールドに CMP-260826-1457 の債務注釈が無い")
        void Entityの債務注釈が外れている() {
            assertThat(Arrays.stream(InviteTokenEntity.class.getDeclaredFields())
                    .filter(field -> isDebt1457(field.getAnnotation(PlaintextTokenDebt.class)))
                    .map(Field::getName).toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("SharedInviteTokenIssuer・MembershipInviteTokenIssuer のメソッドに CMP-260826-1457 の債務注釈が無い")
        void 発行者の債務注釈が外れている() {
            for (Class<?> issuer : List.of(SharedInviteTokenIssuer.class, MembershipInviteTokenIssuer.class)) {
                assertThat(Arrays.stream(issuer.getDeclaredMethods())
                        .filter(method -> isDebt1457(method.getAnnotation(PlaintextTokenDebt.class)))
                        .map(Method::getName).toList())
                        .as("%s に債務注釈が残っている", issuer.getSimpleName())
                        .isEmpty();
            }
        }
    }

    private static boolean isDebt1457(PlaintextTokenDebt debt) {
        return debt != null && DEBT_CMP.equals(debt.cmp());
    }

    private static Set<String> fieldNames(Class<?> type) {
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents()).map(component -> component.getName())
                    .collect(Collectors.toSet());
        }
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(Field::getName).collect(Collectors.toSet());
    }

    private static Optional<Field> findField(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredFields()).filter(field -> field.getName().equals(name)).findFirst();
    }

    private static Set<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getDeclaredMethod(name, parameterTypes);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private static Optional<Class<?>> loadClass(String name) {
        try {
            return Optional.of(Class.forName(name));
        } catch (ClassNotFoundException absent) {
            return Optional.empty();
        }
    }
}
