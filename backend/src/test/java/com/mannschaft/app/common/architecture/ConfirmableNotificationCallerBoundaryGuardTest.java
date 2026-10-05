package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260930-1932 AC-10: {@code ConfirmableNotificationService} のクラス単位免除が
 * <b>呼び出し元まで沈黙させていた</b>問題の番人化（{@link NotificationTransactionBoundaryGuardTest} の拡張）。
 *
 * <h2>現状の死角（red の理由）</h2>
 * <p>{@code AUDITED_EXCEPTIONS} にクラス粒度で載っているため、{@code notificationFiringMethods} が
 * 空集合を返し、業務TX（{@code @Transactional}）から {@code confirmableNotificationService.send(...)} /
 * {@code sendFromSource(...)} を呼ぶあらゆる呼び出し元が {@code TX_NOTIFY_VIA_DELEGATE} から外れていた。
 * そのため {@code RecruitmentAutoCancelBatch#processSingleListing} と
 * {@code MarketFinalizeService#sendFinalizeConfirmation} の業務TX内同期送信が一度も赤くならなかった
 * （規約 原則5-1「免除はクラス単位にしてはならない」の違反）。</p>
 *
 * <h2>根治後の契約（御裁可 2026-10-01・Codex 採用追記）</h2>
 * <ul>
 *   <li>業務TXから同期 {@code send/sendFromSource} を呼ぶ呼び出し元は番人が挙げる。</li>
 *   <li>ただし「通知そのものが業務目的で、業務TXと同時に巻き戻すのが正しい」監査済みの2経路
 *       {@code CommitteeDistributionService#distribute} と {@code PaymentRequestService#send} だけは許す
 *       （<b>メソッド粒度</b>。同じクラスの別メソッドは許さない）。</li>
 *   <li>許可された入口（{@code AFTER_COMMIT} の {@code @TransactionalEventListener}）からの呼び出しは従来どおり許す。</li>
 *   <li>上記を検体（変異テスト）で固定する。番人の判定方式（どのリストに何を載せるか）は出陣に委ねる。</li>
 * </ul>
 */
@DisplayName("CMP-260930-1932 AC-10: 確認通知の同期送信の呼び出し元を番人が沈黙させない")
class ConfirmableNotificationCallerBoundaryGuardTest {

    private static final String CONFIRMABLE_SERVICE_FQCN =
            "com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService";
    private static final String COMMITTEE_FQCN = "com.mannschaft.app.committee.service.CommitteeDistributionService";
    private static final String PAYMENT_FQCN = "com.mannschaft.app.payment.service.PaymentRequestService";

    /** 業務TX内で同期送信する形の検体（{@code %s} = package, クラス名, メソッド名）。 */
    private static String txCallerSource(String pkg, String simpleName, String methodName, String call) {
        return """
                package %s;

                import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                import java.util.List;

                @Service
                @RequiredArgsConstructor
                public class %s {

                    private final ConfirmableNotificationService confirmableNotificationService;

                    @Transactional
                    public void %s(List<Long> recipientUserIds) {
                        %s
                    }
                }
                """.formatted(pkg, simpleName, methodName, call);
    }

    private static final String SEND_CALL =
            "confirmableNotificationService.send(null, 1L, \"t\", \"b\", null, null, null, null, null, null, null,"
                    + " null, null, 1L, recipientUserIds);";
    private static final String SEND_FROM_SOURCE_CALL =
            "confirmableNotificationService.sendFromSource(\"X\", 1L, null, 1L, \"t\", \"b\", null, null, null,"
                    + " 1L, recipientUserIds);";

    private static Set<String> violatingMethods(String fqcn, String source) {
        return NotificationTransactionBoundaryGuardTest.scanSource(fqcn, source).stream()
                .map(NotificationTransactionBoundaryGuardTest.Violation::methodName)
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------
    // 変異テスト（検体）: 違反形を番人が赤にすること／監査済み・許可入口は赤にしないこと
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC-10 変異テスト（検体）")
    class Mutation {

        @Test
        @DisplayName("AC-10: recruitment パッケージの @Transactional メソッドから send を直接呼ぶと番人が違反として挙げる")
        void AC10_recruitmentの業務TXからsendを呼ぶと赤() {
            String fqcn = "com.mannschaft.app.recruitment.service.Cmp1932SendMutationFixture";
            String src = txCallerSource("com.mannschaft.app.recruitment.service",
                    "Cmp1932SendMutationFixture", "cancelInsideBusinessTx", SEND_CALL);

            assertThat(violatingMethods(fqcn, src))
                    .as("AC-10: 業務TX内の同期 send は番人が挙げるべき（現状はクラス単位免除で沈黙する）")
                    .contains("cancelInsideBusinessTx");
        }

        @Test
        @DisplayName("AC-10: recruitment パッケージの @Transactional メソッドから sendFromSource を直接呼ぶと番人が違反として挙げる")
        void AC10_recruitmentの業務TXからsendFromSourceを呼ぶと赤() {
            String fqcn = "com.mannschaft.app.recruitment.service.Cmp1932SendFromSourceMutationFixture";
            String src = txCallerSource("com.mannschaft.app.recruitment.service",
                    "Cmp1932SendFromSourceMutationFixture", "finalizeInsideBusinessTx", SEND_FROM_SOURCE_CALL);

            assertThat(violatingMethods(fqcn, src))
                    .as("AC-10: 業務TX内の同期 sendFromSource は番人が挙げるべき（現状はクラス単位免除で沈黙する）")
                    .contains("finalizeInsideBusinessTx");
        }

        @Test
        @DisplayName("AC-10: recruitment 以外の任意ドメインでも、監査済みでない呼び出し元の業務TX内 send は挙げる")
        void AC10_他ドメインの未監査呼び出し元も赤() {
            String fqcn = "com.mannschaft.app.cmp1932fixture.service.Cmp1932OtherDomainFixture";
            String src = txCallerSource("com.mannschaft.app.cmp1932fixture.service",
                    "Cmp1932OtherDomainFixture", "doBusiness", SEND_CALL);

            assertThat(violatingMethods(fqcn, src))
                    .as("AC-10: 免除は監査済みの2経路に限る。未監査の呼び出し元は番人が挙げる")
                    .contains("doBusiness");
        }

        @Test
        @DisplayName("AC-10: 監査済み例外は『メソッド粒度』— CommitteeDistributionService の別メソッドからの send は挙げる")
        void AC10_監査済みクラスでも別メソッドは赤() {
            String src = txCallerSource("com.mannschaft.app.committee.service",
                    "CommitteeDistributionService", "someNewBusinessMethod", SEND_CALL);

            assertThat(violatingMethods(COMMITTEE_FQCN, src))
                    .as("AC-10: 呼び出し元の免除はクラス単位にしてはならない（原則5-1）。"
                            + "監査済みは distribute だけで、同じクラスの新しいメソッドは挙がるべき")
                    .contains("someNewBusinessMethod");
        }

        @Test
        @DisplayName("AC-10: 監査済み2経路（CommitteeDistributionService#distribute / PaymentRequestService#send）は挙げない")
        void AC10_監査済み2経路は挙げない() {
            assertThat(violatingMethods(COMMITTEE_FQCN, txCallerSource("com.mannschaft.app.committee.service",
                    "CommitteeDistributionService", "distribute", SEND_CALL)))
                    .as("AC-10: CommitteeDistributionService#distribute は監査済み（委員会伝達＝通知が業務目的）")
                    .doesNotContain("distribute");
            assertThat(violatingMethods(PAYMENT_FQCN, txCallerSource("com.mannschaft.app.payment.service",
                    "PaymentRequestService", "send", SEND_CALL)))
                    .as("AC-10: PaymentRequestService#send は監査済み（支払い依頼＝通知が業務目的）")
                    .doesNotContain("send");
        }

        @Test
        @DisplayName("AC-10: AFTER_COMMIT の @TransactionalEventListener から sendFromSource を呼ぶ形は挙げない（正規形）")
        void AC10_AFTER_COMMITリスナーからの呼び出しは挙げない() {
            String fqcn = "com.mannschaft.app.notification.confirmable.event.Cmp1932ListenerFixture";
            String src = """
                    package com.mannschaft.app.notification.confirmable.event;

                    import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
                    import lombok.RequiredArgsConstructor;
                    import org.springframework.scheduling.annotation.Async;
                    import org.springframework.stereotype.Component;
                    import org.springframework.transaction.event.TransactionPhase;
                    import org.springframework.transaction.event.TransactionalEventListener;

                    import java.util.List;

                    @Component
                    @RequiredArgsConstructor
                    public class Cmp1932ListenerFixture {

                        private final ConfirmableNotificationService confirmableNotificationService;

                        @Async("event-pool")
                        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
                        public void onEvent(List<Long> recipientUserIds) {
                            try {
                                confirmableNotificationService.sendFromSource("X", 1L, null, 1L, "t", "b", null, null,
                                        null, 1L, recipientUserIds);
                            } catch (Exception e) {
                                throw e;
                            }
                        }
                    }
                    """;

            assertThat(violatingMethods(fqcn, src))
                    .as("AC-10: AFTER_COMMIT リスナーは許可された入口であり、違反ではない")
                    .doesNotContain("onEvent");
        }
    }

    // ------------------------------------------------------------------
    // 本番ソース: 免除の見直しと、対象経路の業務TX内同期送信が無いこと
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC-10 本番ソース")
    class MainSource {

        @Test
        @DisplayName("AC-10: ConfirmableNotificationService をクラス単位の監査済み例外に置かない（呼び出し元を沈黙させない）")
        void AC10_確認通知サービスのクラス単位免除を外す() {
            assertThat(NotificationTransactionBoundaryGuardTest.AUDITED_EXCEPTIONS)
                    .as("AC-10: クラス単位免除は委譲する全呼び出し元を TX_NOTIFY_VIA_DELEGATE から外す（原則5-1）")
                    .doesNotContain(CONFIRMABLE_SERVICE_FQCN);
        }

        @Test
        @DisplayName("AC-10: recruitment パッケージの @Transactional 文脈に ConfirmableNotificationService#send/sendFromSource の直接呼び出しが無い")
        void AC10_recruitmentの業務TX内に確認通知の同期送信が無い() throws Exception {
            Path root = NotificationTransactionBoundaryGuardTest.mainSourceRoot().resolve("com/mannschaft/app/recruitment");
            assertThat(Files.isDirectory(root)).as("recruitment パッケージの走査が空振りしている").isTrue();

            List<String> offenders = new ArrayList<>();
            Pattern call = Pattern.compile("([\\w$]+)\\s*\\.\\s*(send|sendFromSource)\\s*\\(");
            int scanned = 0;
            for (Path file : NotificationTransactionBoundaryGuardTest.javaFiles(root)) {
                scanned++;
                String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(
                        NotificationTransactionBoundaryGuardTest.read(file));
                String simpleName = file.getFileName().toString().replace(".java", "");
                String classAnnotations = NotificationTransactionBoundaryGuardTest.typeAnnotations(masked, simpleName);
                List<NotificationTransactionBoundaryGuardTest.MethodBlock> methods =
                        NotificationTransactionBoundaryGuardTest.parseMethods(masked);
                Set<String> txClosure = NotificationTransactionBoundaryGuardTest.transactionalClosure(
                        methods, classAnnotations == null ? "" : classAnnotations);
                Map<String, Set<String>> declared = NotificationTransactionBoundaryGuardTest.declaredTypes(masked);
                for (NotificationTransactionBoundaryGuardTest.MethodBlock m : methods) {
                    if (NotificationTransactionBoundaryGuardTest.isAllowedEntryPoint(m.annotations())) {
                        continue;
                    }
                    boolean inTx = NotificationTransactionBoundaryGuardTest.hasTransactional(m.annotations())
                            || NotificationTransactionBoundaryGuardTest.hasTransactional(
                                    classAnnotations == null ? "" : classAnnotations)
                            || txClosure.contains(m.name());
                    if (!inTx) {
                        continue;
                    }
                    Matcher matcher = call.matcher(m.body());
                    while (matcher.find()) {
                        Set<String> types = declared.getOrDefault(matcher.group(1), Set.of());
                        if (types.contains("ConfirmableNotificationService")) {
                            offenders.add(simpleName + "#" + m.name() + " -> " + matcher.group(1) + "." + matcher.group(2));
                        }
                    }
                }
            }
            assertThat(scanned).as("recruitment パッケージのソースが1件も読めていない").isGreaterThan(10);
            assertThat(offenders)
                    .as("AC-10: 自動キャンセル／最終認証の業務TX内で確認通知を同期送信している"
                            + "（AFTER_COMMIT リスナー経由へ移すこと。現状 RecruitmentAutoCancelBatch#processSingleListing と"
                            + " MarketFinalizeService#sendFinalizeConfirmation が該当）")
                    .isEmpty();
        }

        @Test
        @DisplayName("AC-10: 本番走査で ConfirmableNotificationService 自身・監査済み2経路は違反に挙がらない")
        void AC10_確認通知サービス自身と監査済み2経路は挙がらない() {
            Set<String> keys = NotificationTransactionBoundaryGuardTest.mainScan().violations().stream()
                    .map(NotificationTransactionBoundaryGuardTest.Violation::key)
                    .collect(Collectors.toSet());
            assertThat(keys)
                    .as("AC-10: 確認通知サービス自身（通知が業務目的）は契約の対象外")
                    .noneMatch(k -> k.startsWith(CONFIRMABLE_SERVICE_FQCN + "#"));
            assertThat(keys)
                    .as("AC-10: 監査済み2経路は違反に挙がらない")
                    .noneMatch(k -> k.startsWith(COMMITTEE_FQCN + "#distribute ")
                            || k.startsWith(PAYMENT_FQCN + "#send "));
        }

        @Test
        @DisplayName("AC-10: 対象経路を凍結リスト（baseline）へ逃がしていない")
        void AC10_対象経路を凍結リストへ逃がしていない() {
            Set<String> frozen = NotificationTransactionBoundaryGuardTest.readFreezeList();
            List<String> prefixes = List.of(
                    CONFIRMABLE_SERVICE_FQCN + "#",
                    COMMITTEE_FQCN + "#distribute ",
                    PAYMENT_FQCN + "#send ",
                    "com.mannschaft.app.recruitment.service.RecruitmentAutoCancelBatch#",
                    "com.mannschaft.app.recruitment.service.MarketFinalizeService#",
                    "com.mannschaft.app.recruitment.service.RecruitmentParticipantService#apply ");
            assertThat(frozen)
                    .as("AC-10: 本戦役の対象経路を新たな負債として凍結してはならない（根治すること）")
                    .noneMatch(k -> prefixes.stream().anyMatch(k::startsWith));
        }
    }
}
