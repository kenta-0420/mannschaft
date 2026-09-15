package com.mannschaft.app.common.transaction;

import com.mannschaft.app.advertising.campaign.entity.AdAnnouncementDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdPushDelivery;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.repository.AdAnnouncementDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.campaign.repository.AdPushDeliveryRepository;
import com.mannschaft.app.advertising.campaign.service.AdMessagingBillingBridge;
import com.mannschaft.app.advertising.repository.AdInvoiceItemRepository;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.migration.StoragePathMigrationBatchService;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.pointcard.batch.PointCardRematchBatchService;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import com.mannschaft.app.pointcard.service.ProviderMatchService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteAttachmentEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteAttachmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-260912-1524 — {@code REQUIRES_NEW} の自己呼び出し 3 件を実 DB で検証する。
 *
 * <h2>何を実証するテストか</h2>
 * <p>是正前、以下の 3 クラスは同一 Bean 内の
 * {@code @Transactional(propagation = REQUIRES_NEW)} メソッドを<b>自己呼び出し</b>していた。
 * Spring の AOP プロキシを経由しないためアノテーションは一切効かず、呼び出し元にも
 * トランザクションが無いため、処理単位は Spring Data 既定の {@code @Transactional} による
 * <b>save ごとの細切れコミット</b>に分解されていた。</p>
 * <ul>
 *   <li>{@link AdMessagingBillingBridge#billOneCampaign} — 請求明細の積み上げ途中で落ちると
 *       そこまでの明細だけが残る。さらに請求書合計の更新は dirty checking に依存するため、
 *       トランザクションが無いと<b>どこにも書かれない</b>（現に合計 0 円のまま）</li>
 *   <li>{@link PointCardRematchBatchService#processChunk} — チャンク単位の巻き戻しが効かず、
 *       途中まで書き換えた {@code provider_id} が残る</li>
 *   <li>{@link StoragePathMigrationBatchService} の {@code migrateOneXxx} —
 *       1 ファイルぶんの移行がトランザクション外で走る</li>
 * </ul>
 * <p>いずれも例外で 500 になるわけではない（CMP-260910-1556 との違い）。
 * <b>症状が出ないまま部分適用が蓄積する</b>のが実害である。</p>
 *
 * <h2>なぜ統合テストなのか</h2>
 * <p>自己呼び出しは「プロキシを経由したか」という実行時の性質であり、サービスを
 * {@code new} して mock を挿すユニットテストでは<b>構造的に検出できない</b>
 * （mock は素通しで成功してしまう）。プロキシが実在する Spring コンテキストと実 DB が要る。</p>
 *
 * <h2>失敗注入の方法</h2>
 * <p>統合テストのスキーマは {@code spring.flyway.enabled=false} + {@code ddl-auto: create} により
 * Hibernate がエンティティから生成しており、Flyway の DDL 制約（FK / CHECK / UNIQUE）は
 * テスト DB に存在しない。DB 制約で失敗を注入すると無言で素通りする。
 * よって「処理単位の後段で落ちる」状況は、処理単位の途中で呼ばれる協調オブジェクトの
 * スタブから {@link TransactionSynchronization#beforeCommit(boolean)} を登録して作る。
 * これは<b>トランザクションが実在しないと登録自体が失敗する</b>ため、
 * 「プロキシを経由してトランザクションが張られていること」の証明も兼ねる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260912-1524 REQUIRES_NEW 自己呼び出しのトランザクション境界（実DB）")
class SelfInvocationRequiresNewTransactionIT extends AbstractMySqlIntegrationTest {

    /** 失敗注入時に投げる例外のメッセージ。 */
    private static final String INJECTED = "CMP-260912-1524: 処理単位の後段の失敗を模す";

    /** {@code ad_*_deliveries.month_key} の書式（本番実装と同じ yyyy-MM）。 */
    private static final DateTimeFormatter MONTH_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ── 検体 1: 広告メッセージ課金ブリッジ ──
    @Autowired
    private AdMessagingBillingBridge billingBridge;

    @Autowired
    private AdMessagingCampaignRepository campaignRepository;

    @Autowired
    private AdAnnouncementDeliveryRepository announcementDeliveryRepository;

    @Autowired
    private AdPushDeliveryRepository pushDeliveryRepository;

    /**
     * 冪等チェックの呼び出しを乗っ取り、キャンペーン処理の途中で
     * 「コミット時に落ちる」状態を仕込む。spy なので他のメソッドは実体に委譲される。
     */
    @MockitoSpyBean
    private AdInvoiceItemRepository invoiceItemRepository;

    // ── 検体 2: ポイントカード再マッチバッチ ──
    @Autowired
    private PointCardRematchBatchService rematchBatchService;

    @Autowired
    private UserPointCardRepository userPointCardRepository;

    @Autowired
    private PointCardProviderRepository providerRepository;

    @MockitoBean
    private ProviderMatchService providerMatchService;

    @MockitoBean
    private ErrorReportService errorReportService;

    // ── 検体 3: ストレージパス移行バッチ ──
    @Autowired
    private StoragePathMigrationBatchService migrationBatchService;

    @Autowired
    private TimetableSlotUserNoteAttachmentRepository noteAttachmentRepository;

    @MockitoBean
    private R2StorageService r2StorageService;

    // ═══════════════════════════════════════════════════════════════
    // 検体 1: AdMessagingBillingBridge
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1 課金ブリッジはキャンペーン単位のトランザクション内で走り、請求書合計まで確定する")
    void 課金ブリッジはトランザクション内で走る() {
        YearMonth month = YearMonth.of(2026, 6);
        long advertiserAccountId = nextId();
        UUID campaignId = seedCampaign(advertiserAccountId, month);
        seedAnnouncementDeliveries(campaignId, month, 3);
        seedPushDeliveries(campaignId, month, 4);

        AtomicBoolean txActive = new AtomicBoolean(false);
        recordTxActiveOnIdempotencyCheck(campaignId, txActive);

        billingBridge.runMonthlyBilling(month);

        assertThat(txActive.get())
                .as("自己呼び出しのままだと billOneCampaign はトランザクション外で走る")
                .isTrue();
        assertThat(invoiceItemCount(campaignId))
                .as("ANNOUNCEMENT と PUSH の 2 明細が積まれること")
                .isEqualTo(2);
        // ANNOUNCEMENT 3 件 × ¥5 + PUSH 4 件 × ¥3 = ¥27、税 10% は FLOOR で ¥2
        assertThat(invoiceTotalAmount(advertiserAccountId)).isEqualByComparingTo(new BigDecimal("27"));
        assertThat(invoiceTaxAmount(advertiserAccountId)).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(invoiceTotalWithTax(advertiserAccountId))
                .as("請求書合計は dirty checking で書かれる。トランザクションが無いと 0 円のまま消える")
                .isEqualByComparingTo(new BigDecimal("29"));
        assertThat(consumedBudgetYen(campaignId)).isEqualTo(27L);
    }

    @Test
    @DisplayName("AC-2 キャンペーン処理の後段で落ちたら、その月の請求明細も請求書も消費予算も残らない")
    void 課金ブリッジは失敗時に部分適用を残さない() {
        YearMonth month = YearMonth.of(2026, 7);
        long advertiserAccountId = nextId();
        UUID campaignId = seedCampaign(advertiserAccountId, month);
        seedAnnouncementDeliveries(campaignId, month, 3);
        seedPushDeliveries(campaignId, month, 4);

        // ANNOUNCEMENT 明細を積んだ後、PUSH の冪等チェックの時点でコミット時失敗を仕込む。
        failAtCommitOnIdempotencyCheck(campaignId, "PUSH");

        // 1 キャンペーンの失敗はバッチ全体を倒さない設計なので、例外は外へ出ない。
        billingBridge.runMonthlyBilling(month);

        assertThat(invoiceItemCount(campaignId))
                .as("ANNOUNCEMENT の明細だけが残ると、合計の合わない請求書が静かに積み上がる（本欠陥の実害）")
                .isZero();
        assertThat(invoiceCount(advertiserAccountId))
                .as("明細が 1 件も無いのに請求書だけ作られてはならない")
                .isZero();
        assertThat(consumedBudgetYen(campaignId)).isZero();
    }

    // ═══════════════════════════════════════════════════════════════
    // 検体 2: PointCardRematchBatchService
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1 再マッチバッチはチャンク単位のトランザクション内で走り、provider_id を確定する")
    void 再マッチバッチはトランザクション内で走る() {
        UUID providerId = seedProvider();
        String name1 = "CMP1524 マッチ対象 A " + nextId();
        String name2 = "CMP1524 マッチ対象 B " + nextId();
        UUID card1 = seedCard(name1);
        UUID card2 = seedCard(name2);

        AtomicBoolean txActive = new AtomicBoolean(false);
        when(providerMatchService.matchProvider(any())).thenReturn(Optional.empty());
        when(providerMatchService.matchProvider(name1)).thenAnswer(inv -> {
            txActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return providerRepository.findById(providerId);
        });
        when(providerMatchService.matchProvider(name2))
                .thenAnswer(inv -> providerRepository.findById(providerId));

        rematchBatchService.execute();

        assertThat(txActive.get())
                .as("自己呼び出しのままだと processChunk はトランザクション外で走る")
                .isTrue();
        assertThat(providerIdOf(card1)).isEqualTo(providerId.toString());
        assertThat(providerIdOf(card2)).isEqualTo(providerId.toString());
    }

    @Test
    @DisplayName("AC-2 チャンクの後段で落ちたら、同じチャンクで書き換えた provider_id は全て巻き戻る")
    void 再マッチバッチは失敗時に部分適用を残さない() {
        UUID providerId = seedProvider();
        String name1 = "CMP1524 巻き戻し対象 A " + nextId();
        String name2 = "CMP1524 巻き戻し対象 B " + nextId();
        UUID card1 = seedCard(name1);
        UUID card2 = seedCard(name2);

        when(providerMatchService.matchProvider(any())).thenReturn(Optional.empty());
        when(providerMatchService.matchProvider(name1))
                .thenAnswer(inv -> providerRepository.findById(providerId));
        when(providerMatchService.matchProvider(name2)).thenAnswer(inv -> {
            throwAtCommit();
            return providerRepository.findById(providerId);
        });

        // チャンクごと落ちた場合はバッチを止めて Sentry へ投げる設計なので、例外は外へ出ない。
        rematchBatchService.execute();

        assertThat(providerIdOf(card1))
                .as("先に処理したカードだけ provider_id が残ると、巻き戻せない部分適用になる（本欠陥の実害）")
                .isNull();
        assertThat(providerIdOf(card2)).isNull();
        verify(errorReportService, atLeastOnce())
                .recordBackendException(any(Throwable.class), isNull(), eq(ErrorReportSeverity.HIGH));
    }

    // ═══════════════════════════════════════════════════════════════
    // 検体 3: StoragePathMigrationBatchService
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1 ストレージパス移行は 1 ファイル単位のトランザクション内で走り、キーを確定する")
    void ストレージ移行はトランザクション内で走る() {
        long userId = nextId();
        String oldKey = "user/" + userId + "/timetable-notes/" + nextId() + ".png";
        Long attachmentId = seedNoteAttachment(userId, oldKey);

        AtomicBoolean txActive = new AtomicBoolean(false);
        doAnswer(inv -> {
            txActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(r2StorageService).copyObject(eq(oldKey), any());

        migrationBatchService.migrateTimetableNoteAttachments();

        assertThat(txActive.get())
                .as("自己呼び出しのままだと migrateOneXxx はトランザクション外で走る")
                .isTrue();
        assertThat(noteObjectKey(attachmentId))
                .isEqualTo("user/PERSONAL/" + userId + "/timetable-notes/" + oldKey.split("/")[3]);
    }

    @Test
    @DisplayName("AC-2 移行単位の後段で落ちたらそのファイルのキー更新は巻き戻り、他ファイルの移行は残る")
    void ストレージ移行は失敗時に部分適用を残さない() {
        long userId = nextId();
        String okKey = "user/" + userId + "/timetable-notes/" + nextId() + "-ok.png";
        String ngKey = "user/" + userId + "/timetable-notes/" + nextId() + "-ng.png";
        Long okId = seedNoteAttachment(userId, okKey);
        Long ngId = seedNoteAttachment(userId, ngKey);

        doAnswer(inv -> {
            throwAtCommit();
            return null;
        }).when(r2StorageService).copyObject(eq(ngKey), any());

        migrationBatchService.migrateTimetableNoteAttachments();

        assertThat(noteObjectKey(okId))
                .as("1 ファイルの失敗が他ファイルの移行を巻き戻してはならない（REQUIRES_NEW の目的）")
                .startsWith("user/PERSONAL/");
        assertThat(noteObjectKey(ngId))
                .as("コピー後の DB 更新が巻き戻らないと、実体の無い新パスを指す行が残る")
                .isEqualTo(ngKey);
        assertThat(migrationErrorCount(ngKey))
                .as("失敗は握りつぶさず storage_migration_errors に記録されること")
                .isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════
    // 失敗注入・観測のヘルパー
    // ═══════════════════════════════════════════════════════════════

    /**
     * 現在のトランザクションのコミット直前に例外を投げる同期を登録する。
     *
     * <p>トランザクションが張られていなければ登録自体が {@code IllegalStateException} で
     * 失敗するため、「プロキシを経由していない」状態でも赤になる。</p>
     */
    private void throwAtCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException(INJECTED);
            }
        });
    }

    /** 指定キャンペーンの冪等チェック時にトランザクションの有無を記録する。 */
    private void recordTxActiveOnIdempotencyCheck(UUID campaignId, AtomicBoolean sink) {
        doAnswer(inv -> {
            sink.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.empty();
        }).when(invoiceItemRepository)
                .findByMessagingCampaignIdAndChannelTypeAndMonthKey(eq(campaignId), any(), any());
    }

    /** 指定キャンペーン・指定チャネルの冪等チェック時点で、コミット時失敗を仕込む。 */
    private void failAtCommitOnIdempotencyCheck(UUID campaignId, String channelType) {
        doAnswer(inv -> {
            throwAtCommit();
            return Optional.empty();
        }).when(invoiceItemRepository)
                .findByMessagingCampaignIdAndChannelTypeAndMonthKey(eq(campaignId), eq(channelType), any());
    }

    // ═══════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═══════════════════════════════════════════════════════════════

    /** UUID 主キーは Hibernate 既定で {@code BINARY(16)} に落ちるため、生 SQL ではバイト列で束縛する。 */
    private static byte[] bin(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    private static long nextId() {
        return SEQ.incrementAndGet();
    }

    private UUID seedCampaign(long advertiserAccountId, YearMonth month) {
        return transactionTemplate.execute(tx -> campaignRepository.save(
                AdMessagingCampaign.builder()
                        .advertiserAccountId(advertiserAccountId)
                        .scopeType(ScopeType.ORGANIZATION)
                        .scopeId(nextId())
                        .name("CMP1524 キャンペーン " + advertiserAccountId)
                        .status(AdCampaignStatus.DELIVERING)
                        .totalBudgetYen(1_000_000L)
                        .consumedBudgetYen(0L)
                        .startsAt(month.atDay(1).atStartOfDay())
                        .endsAt(month.atEndOfMonth().atTime(23, 59, 59))
                        .scheduledTimezone("Asia/Tokyo")
                        .moderationStatus(AdModerationStatus.APPROVED)
                        .createdByUserId(nextId())
                        .build()).getId());
    }

    private void seedAnnouncementDeliveries(UUID campaignId, YearMonth month, int count) {
        String monthKey = month.format(MONTH_KEY_FMT);
        LocalDateTime deliveredAt = month.atDay(2).atTime(10, 0);
        transactionTemplate.executeWithoutResult(tx -> {
            for (int i = 0; i < count; i++) {
                announcementDeliveryRepository.save(AdAnnouncementDelivery.builder()
                        .campaignId(campaignId)
                        .userId(nextId())
                        .announcementFeedId(nextId())
                        .deliveredAt(deliveredAt)
                        .monthKey(monthKey)
                        .build());
            }
        });
    }

    private void seedPushDeliveries(UUID campaignId, YearMonth month, int count) {
        String monthKey = month.format(MONTH_KEY_FMT);
        LocalDateTime deliveredAt = month.atDay(3).atTime(11, 0);
        transactionTemplate.executeWithoutResult(tx -> {
            for (int i = 0; i < count; i++) {
                pushDeliveryRepository.save(AdPushDelivery.builder()
                        .campaignId(campaignId)
                        .userId(nextId())
                        .notificationId(nextId())
                        .deliveredAt(deliveredAt)
                        .monthKey(monthKey)
                        .build());
            }
        });
    }

    private UUID seedProvider() {
        return transactionTemplate.execute(tx -> providerRepository.save(
                PointCardProviderEntity.builder()
                        .code("cmp1524-" + nextId())
                        .displayName("CMP1524 プロバイダー")
                        .category(PointCardCategory.OTHER)
                        .build()).getId());
    }

    private UUID seedCard(String displayName) {
        return transactionTemplate.execute(tx -> userPointCardRepository.save(
                UserPointCardEntity.builder()
                        .userId(nextId())
                        .displayName(displayName)
                        .barcodeValue("1234567890123")
                        .barcodeFormat(BarcodeFormat.CODE128)
                        .last4("0123")
                        .build()).getId());
    }

    private Long seedNoteAttachment(long userId, String objectKey) {
        return transactionTemplate.execute(tx -> noteAttachmentRepository.save(
                TimetableSlotUserNoteAttachmentEntity.builder()
                        .noteId(nextId())
                        .userId(userId)
                        .r2ObjectKey(objectKey)
                        .originalFilename("note.png")
                        .mimeType("image/png")
                        .sizeBytes(1024L)
                        .createdAt(LocalDateTime.of(2026, 6, 1, 9, 0))
                        .build()).getId());
    }

    // ═══════════════════════════════════════════════════════════════
    // 検証クエリ
    // ═══════════════════════════════════════════════════════════════

    private int invoiceItemCount(UUID campaignId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ad_invoice_items WHERE messaging_campaign_id = ?",
                Integer.class, bin(campaignId));
        return n == null ? 0 : n;
    }

    private int invoiceCount(long advertiserAccountId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ad_invoices WHERE advertiser_account_id = ?",
                Integer.class, advertiserAccountId);
        return n == null ? 0 : n;
    }

    private BigDecimal invoiceTotalAmount(long advertiserAccountId) {
        return invoiceColumn(advertiserAccountId, "total_amount");
    }

    private BigDecimal invoiceTaxAmount(long advertiserAccountId) {
        return invoiceColumn(advertiserAccountId, "tax_amount");
    }

    private BigDecimal invoiceTotalWithTax(long advertiserAccountId) {
        return invoiceColumn(advertiserAccountId, "total_with_tax");
    }

    private BigDecimal invoiceColumn(long advertiserAccountId, String column) {
        List<BigDecimal> rows = jdbcTemplate.queryForList(
                "SELECT " + column + " FROM ad_invoices WHERE advertiser_account_id = ?",
                BigDecimal.class, advertiserAccountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long consumedBudgetYen(UUID campaignId) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT consumed_budget_yen FROM ad_messaging_campaigns WHERE id = ?",
                Long.class, bin(campaignId));
        return v == null ? 0L : v;
    }

    private String providerIdOf(UUID cardId) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_id FROM user_point_cards WHERE id = ?",
                String.class, cardId.toString());
    }

    private String noteObjectKey(Long attachmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT r2_object_key FROM timetable_slot_user_note_attachments WHERE id = ?",
                String.class, attachmentId);
    }

    private int migrationErrorCount(String oldKey) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM storage_migration_errors WHERE old_file_key = ?",
                Integer.class, oldKey);
        return n == null ? 0 : n;
    }
}
