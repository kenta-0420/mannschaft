package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 確認通知 W3b（CMP-260923-0954）の構造の固定（試練 / red 先行）。
 *
 * <p>正本: {@code ep_tables_w3b.md} §8・§9・殿の判断8・補足（ファサード化は recipients/page のみ）、
 * {@code plan4_tx_facade.md} AC-13〜15・K3・K7。挙動（応答）は {@code ConfirmableNotificationExistenceOracleContractIT}
 * と {@code ConfirmableNotificationConfirmOracleIT} が持つ。</p>
 *
 * <ol>
 *   <li>AC-15: recipients/page は Facade（{@value #FACADE}。非 tx）で認可（viewerRole の判定）し、tx 本体
 *       （{@code ConfirmableNotificationQueryService} / {@code ConfirmableNotificationService}）は
 *       {@code AccessControlService} / Gate に依存しない。Facade に {@code @Transactional} が無い。</li>
 *   <li>K7: Team/Org の Controller の {@code getRecipientsPage} は Facade を呼び、tx 本体の
 *       {@code getRecipientsPage} を直接呼ばない。Facade は実際に {@code AccessControlService} へ届く。</li>
 *   <li>Facade の名前は {@code *Facade}（{@code *AccessService} 等にすると AuthzControllerGuard が呼んだだけで
 *       認可扱いし、Facade 内の認可漏れを見逃す）。</li>
 *   <li>殿の判断8・K5: {@code SCOPE_MISMATCH} の投げ元は0（enum を消しても、残しても、参照が無ければ緑）。
 *       {@code RECIPIENT_NOT_FOUND} は廃止済み（非受信者・除外済みは NOT_FOUND に畳む）。</li>
 *   <li>K3: cancel の D-3T 凍結キー（{@code UserRepository} 到達・認可と無関係）は残す前提。
 *       {@code ConfirmableNotificationService.cancel(Long, Long)} と
 *       {@code ConfirmableNotificationConfirmService.cancel(Long, Long)} の名前・引数・{@code @Transactional} を変えない
 *       （変えると凍結キーが変わって新規違反＝既存キーの消失と新規キーの追加になる）。</li>
 *   <li>AC-13/14・K2: D-3T 凍結ストアから recipients/page の 4 キーが消え（認可由来の越境到達が無くなる）、
 *       cancel の 2 キーは残る。ストアはテスト実行で自動的に行が消える（refreeze=false）ので、出陣で D-3T を
 *       実行してストアと {@code EXPECTED_LINES_CROSS_DOMAIN_TX_D3T} を一緒にコミットする。</li>
 * </ol>
 *
 * <p>Facade クラスは出陣で新設する。本テストは文字列のクラス名で参照するので、実装前でもコンパイルが通る
 * （実装前は「対象クラスが実在する」等が赤）。</p>
 */
@DisplayName("確認通知 W3b の構造固定（recipients/page ファサード・SCOPE_MISMATCH 投げ元0・cancel の凍結キー）")
class ConfirmableRecipientPageFacadeArchTest {

    private static final String PKG = "com.mannschaft.app.notification.confirmable";
    private static final String FACADE = PKG + ".service.ConfirmableNotificationRecipientPageFacade";
    private static final String QUERY_SERVICE = PKG + ".service.ConfirmableNotificationQueryService";
    private static final String SERVICE = PKG + ".service.ConfirmableNotificationService";
    private static final String CONFIRM_SERVICE = PKG + ".service.ConfirmableNotificationConfirmService";
    private static final String ERROR_CODE = PKG + ".error.ConfirmableNotificationErrorCode";
    private static final List<String> CONTROLLERS = List.of(
            PKG + ".controller.TeamConfirmableNotificationController",
            PKG + ".controller.OrgConfirmableNotificationController");
    private static final String ACCESS_CONTROL = "com.mannschaft.app.common.AccessControlService";
    private static final String GATE = "com.mannschaft.app.common.ScopeConcealingAccessGate";

    private static final Path D3T_STORE = Paths.get("src", "test", "resources", "archunit_store",
            "296295dd-06cf-4f7b-bf82-315ba12ff501");

    private final JavaClasses classes = ProductionClasses.get();

    @Test
    @DisplayName("対象クラスが実在する（Facade の新設・リネームで番人が空振りしない）")
    void 対象クラスが実在する() {
        for (String name : List.of(FACADE, QUERY_SERVICE, SERVICE, CONFIRM_SERVICE, CONTROLLERS.get(0),
                CONTROLLERS.get(1))) {
            assertThat(classes.contain(name)).as(name).isTrue();
        }
    }

    @Test
    @DisplayName("AC-15: recipients/page の Facade に @Transactional が無い（クラスにもメソッドにも）")
    void Facadeにtransactionalが無い() {
        JavaClass facade = requireClass(FACADE);
        assertThat(facade.isAnnotatedWith(Transactional.class) || facade.isMetaAnnotatedWith(Transactional.class))
                .as("Facade のクラスに @Transactional がある").isFalse();
        for (JavaMethod method : facade.getMethods()) {
            assertThat(method.isAnnotatedWith(Transactional.class) || method.isMetaAnnotatedWith(Transactional.class))
                    .as(method.getFullName() + " に @Transactional がある").isFalse();
        }
    }

    @Test
    @DisplayName("AC-15/K7: Facade は AccessControlService へ実際に届く（認可が空洞化していない）")
    void Facadeは認可クラスに届く() {
        JavaClass facade = requireClass(FACADE);
        boolean reaches = facade.getMethodCallsFromSelf().stream()
                .anyMatch(c -> c.getTargetOwner().getName().equals(ACCESS_CONTROL)
                        || c.getTargetOwner().getName().equals(GATE));
        assertThat(reaches).as("Facade が AccessControlService / Gate を呼んでいない").isTrue();
    }

    @Test
    @DisplayName("AC-15: tx 本体（QueryService / Service）は AccessControlService / ScopeConcealingAccessGate に依存しない")
    void tx本体は認可クラスに依存しない() {
        noClasses().that().haveFullyQualifiedName(QUERY_SERVICE).or().haveFullyQualifiedName(SERVICE)
                .should().dependOnClassesThat().haveFullyQualifiedName(ACCESS_CONTROL)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(GATE)
                .because("認可は tx の外の Facade に置く。クラス注釈の tx 内に残すと D-3T が common 経由の越境と数える")
                .check(classes);
    }

    @Test
    @DisplayName("K7: Team/Org の Controller の getRecipientsPage は Facade を呼び、tx 本体の getRecipientsPage を直接呼ばない")
    void ControllerはFacade経由でrecipientsPageを返す() {
        for (String controllerName : CONTROLLERS) {
            JavaClass controller = requireClass(controllerName);
            List<JavaMethod> methods = controller.getMethods().stream()
                    .filter(m -> m.getName().equals("getRecipientsPage")).toList();
            assertThat(methods).as(controllerName + "#getRecipientsPage").hasSize(1);
            JavaMethod method = methods.get(0);
            boolean callsFacade = method.getMethodCallsFromSelf().stream()
                    .anyMatch(c -> c.getTargetOwner().getName().equals(FACADE));
            boolean callsTxBody = method.getMethodCallsFromSelf().stream()
                    .anyMatch(c -> (c.getTargetOwner().getName().equals(SERVICE)
                            || c.getTargetOwner().getName().equals(QUERY_SERVICE))
                            && c.getName().equals("getRecipientsPage"));
            assertThat(callsFacade).as(method.getFullName() + " が Facade を呼んでいない").isTrue();
            assertThat(callsTxBody).as(method.getFullName() + " が認可を飛ばして tx 本体を直接呼んでいる").isFalse();
        }
    }

    @Test
    @DisplayName("Facade の名前は *Facade（*AccessService / *AccessGuard / *AccessGate にしない）")
    void Facadeの名前() {
        String simple = FACADE.substring(FACADE.lastIndexOf('.') + 1);
        assertThat(simple).endsWith("Facade").doesNotEndWith("AccessService").doesNotEndWith("AccessGuard")
                .doesNotEndWith("AccessGate");
    }

    @Test
    @DisplayName("殿の判断8: CONFIRMABLE_NOTIFICATION_SCOPE_MISMATCH の投げ元が0（越境は NOT_FOUND / TEMPLATE_NOT_FOUND に畳む）")
    void SCOPE_MISMATCHの投げ元が0() {
        noClasses().that().doNotHaveFullyQualifiedName(ERROR_CODE)
                .should().accessField(ERROR_CODE, "SCOPE_MISMATCH")
                .because("他スコープの ID を不在と別コードで返すと実在が割れる（存在オラクル）")
                .check(classes);
    }

    @Test
    @DisplayName("K5-2: RECIPIENT_NOT_FOUND は廃止済み（非受信者・除外済み受信者は NOT_FOUND に畳む）")
    void RECIPIENT_NOT_FOUNDは廃止済み() {
        assertThat(requireClass(ERROR_CODE).getFields().stream().map(f -> f.getName()))
                .as("専用コードが残ると、非受信者の応答が不在と割れて通知の実在が受信者以外に分かる（C1・AC-7b）")
                .doesNotContain("RECIPIENT_NOT_FOUND");
        noClasses().that().doNotHaveFullyQualifiedName(ERROR_CODE)
                .should().accessField(ERROR_CODE, "RECIPIENT_NOT_FOUND")
                .check(classes);
    }

    @Test
    @DisplayName("K3: cancel の tx 入口（Service / ConfirmService の cancel(Long, Long)）は名前・引数・@Transactional を変えない")
    void cancelの凍結キーの入口を変えない() {
        for (String owner : List.of(SERVICE, CONFIRM_SERVICE)) {
            JavaClass c = requireClass(owner);
            Optional<JavaMethod> cancel = c.getMethods().stream()
                    .filter(m -> m.getName().equals("cancel"))
                    .filter(m -> m.getRawParameterTypes().size() == 2
                            && m.getRawParameterTypes().stream().allMatch(t -> t.getName().equals("java.lang.Long")))
                    .findFirst();
            assertThat(cancel).as(owner + ".cancel(Long, Long)").isPresent();
            assertThat(cancel.get().isAnnotatedWith(Transactional.class))
                    .as(owner + ".cancel(Long, Long) のメソッド @Transactional（凍結キーの入口であり続けること）").isTrue();
        }
    }

    @Test
    @DisplayName("AC-13/14・K2: D-3T 凍結ストアから recipients/page の 4 キーが消え、cancel の 2 キーは残る")
    void D3T凍結ストアのキー集合() throws IOException {
        assertThat(Files.exists(D3T_STORE)).as("D-3T 凍結ストア " + D3T_STORE.toAbsolutePath()).isTrue();
        List<String> lines = Files.readAllLines(D3T_STORE, StandardCharsets.UTF_8);
        List<String> recipientsPage = lines.stream()
                .filter(l -> l.contains(QUERY_SERVICE + ".getRecipientsPage(")
                        || l.contains(SERVICE + ".getRecipientsPage("))
                .toList();
        assertThat(recipientsPage).as("recipients/page の認可由来の越境到達（RoleRepository / UserRoleRepository）は消える")
                .isEmpty();
        assertThat(lines).as("Facade は tx 入口ではないので凍結ストアに現れない")
                .noneMatch(l -> l.contains(FACADE));
        for (String owner : List.of(SERVICE, CONFIRM_SERVICE)) {
            String key = "@Transactional entry " + owner + ".cancel(java.lang.Long, java.lang.Long) "
                    + "(domain 'notification') reaches other-domain repository "
                    + "com.mannschaft.app.auth.repository.UserRepository (domain 'auth') [D-3T]";
            assertThat(lines).as("cancel の凍結キーは残す（K3）: " + key).contains(key);
        }
    }

    private JavaClass requireClass(String name) {
        assertThat(classes.contain(name)).as(name + " が実在すること").isTrue();
        return classes.get(name);
    }
}
