package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.dto.BlockedChildDto;
import com.mannschaft.app.auth.dto.SwitchableChildDto;
import com.mannschaft.app.auth.dto.SwitchableChildrenResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F08.9 P3a 後見切替の集約サービス（切替可能な子の列挙）。
 *
 * <p>認証ユーザー（保護者・払い手）が後見切替できる子を集約する。子の権原は 2 経路で成立する:</p>
 * <ul>
 *   <li>auth: {@link ParentalConsentService#listApprovedChildUserIds}（parental_consent APPROVED）</li>
 *   <li>family: {@link CareLinkService#listActiveParentWatchedRecipientIds}（care_links ACTIVE PARENT）</li>
 * </ul>
 *
 * <p>各ドメインの Service が返す<b>ユーザーID のみ</b>を受け取り（Entity 直接参照禁止・ドメイン境界遵守）、
 * その和集合に対して年齢ポリシー（{@link GuardianshipAgePolicyRegistry}）で切替可否を判定する。
 * 子の生年月日・国コードは {@link UserEntity}（暗号化 {@code birthDate}）を都度復号して解決する。</p>
 *
 * <p><b>TODO（将来最適化）</b>: 03_security §3.1 のとおり、年齢段階判定は本来 @Scheduled バッチで
 * 事前算出（{@code switchAllowed}/{@code stageKey} のスナップショット）し、ホットパスで復号を持ち回らない設計。
 * 本実装は MVP として都度復号で算出する。境界日（年度末・誕生日）の再計算バッチは別フェーズで実装する。</p>
 *
 * <p>閲覧者は常に「自分（認証ユーザー）」のみ。他人の保護者一覧を覗く経路は提供しない（IDOR 防止）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GuardianshipSwitchService {

    /** 封印理由の i18n ラベルキー（年齢到達による封印）。 */
    private static final String REASON_AGE_LOCKED = "AGE_LOCKED";

    /** proxy_input_records.target_entity_type の後見切替用固定値。 */
    private static final String SWITCH_TARGET_ENTITY_TYPE = "GUARDIANSHIP_SWITCH";

    /**
     * proxy_input_records.original_storage_location は NOT NULL。
     * 後見切替は紙原本を伴わないオンライン代理のため、保管場所の概念がない旨の固定値を入れる。
     */
    private static final String SWITCH_STORAGE_LOCATION_NA = "N/A (online guardianship switch)";

    private final ParentalConsentService parentalConsentService;
    private final CareLinkService careLinkService;
    private final UserRepository userRepository;
    private final GuardianshipAgePolicyRegistry agePolicyRegistry;
    private final AuditLogService auditLogService;
    private final ProxyInputRecordRepository proxyInputRecordRepository;
    private final Clock clock;

    /**
     * 認証ユーザー（保護者）の切替可能な子・封印された子を集約して返す。
     *
     * @param guardianUserId 保護者（払い手＝閲覧者本人）のユーザーID
     * @return 切替可能な子（{@code children}）と封印された子（{@code blockedChildren}）
     */
    public SwitchableChildrenResponse listSwitchableChildren(Long guardianUserId) {
        // 2 経路の権原から候補子IDを和集合化（重複排除・順序保持）。
        Set<Long> childUserIds = new LinkedHashSet<>();
        childUserIds.addAll(parentalConsentService.listApprovedChildUserIds(guardianUserId));
        childUserIds.addAll(careLinkService.listActiveParentWatchedRecipientIds(guardianUserId));

        // 自分自身を子として扱わない（防御的・データ不整合対策）。
        childUserIds.remove(guardianUserId);

        if (childUserIds.isEmpty()) {
            return new SwitchableChildrenResponse(List.of(), List.of());
        }

        // 子の属性（表示名・暗号化 birthDate・国コード）を一括ロード（N+1 防止）。
        List<UserEntity> childUsers = userRepository.findByIdIn(childUserIds);

        List<SwitchableChildDto> switchable = new ArrayList<>();
        List<BlockedChildDto> blocked = new ArrayList<>();

        for (UserEntity child : childUsers) {
            LocalDate birthDate = parseBirthDate(child);
            if (birthDate == null) {
                // 生年月日が無い／復号不能な子は安全側で封印に倒す（症状を隠さず記録）。
                log.warn("後見切替: 子 userId={} の birthDate が解決できないため切替を封印（安全側）", child.getId());
                blocked.add(new BlockedChildDto(
                        child.getId(), child.getDisplayName(), "independent", false, REASON_AGE_LOCKED));
                continue;
            }

            GuardianshipAgePolicy policy = agePolicyRegistry.forCountry(child.getCountryCode());
            AgeStageResolution resolution = policy.resolve(birthDate, clock);

            if (resolution.switchAllowed()) {
                switchable.add(new SwitchableChildDto(
                        child.getId(), child.getDisplayName(), resolution.stageKey(), true));
            } else {
                blocked.add(new BlockedChildDto(
                        child.getId(), child.getDisplayName(), resolution.stageKey(), false, REASON_AGE_LOCKED));
            }
        }

        return new SwitchableChildrenResponse(switchable, blocked);
    }

    /**
     * 後見切替の権原検証結果（副作用なし）。
     *
     * <p>{@link #evaluateSwitch} が返す。開始 API（監査あり）と
     * {@link com.mannschaft.app.proxy.ProxyInputContextFilter} の毎リクエスト再検証（監査なし）で
     * 同一の判定ロジックを共有するための純粋な verdict。</p>
     */
    public enum SwitchVerdict {
        /** 切替可（リンク有効 かつ 年齢ゲート OK）。 */
        ALLOWED,
        /** 有効な保護者リンクがない（IDOR 含む）。 */
        LINK_NOT_FOUND,
        /** 年齢到達で封印（生年月日解決不能の安全側封印も含む）。 */
        AGE_LOCKED
    }

    /**
     * 後見切替の権原（保護者リンク → 年齢ゲートの順）を副作用なしで検証する。
     *
     * <p>切替開始 API と毎リクエスト再検証（フィルター）で共有する。監査記録・例外送出は行わず
     * {@link SwitchVerdict} を返すのみ（呼び出し側が用途に応じて例外/HTTP 応答へ変換する）。</p>
     *
     * @param guardianUserId 保護者（acting-as する側）のユーザーID
     * @param childUserId    切替対象の子のユーザーID
     * @return 判定結果
     */
    public SwitchVerdict evaluateSwitch(Long guardianUserId, Long childUserId) {
        if (guardianUserId == null || childUserId == null) {
            return SwitchVerdict.LINK_NOT_FOUND;
        }
        boolean linked = parentalConsentService.isApprovedGuardian(guardianUserId, childUserId)
                || careLinkService.isActiveParentWatcher(guardianUserId, childUserId);
        if (!linked) {
            return SwitchVerdict.LINK_NOT_FOUND;
        }
        UserEntity child = userRepository.findById(childUserId).orElse(null);
        if (child == null) {
            return SwitchVerdict.LINK_NOT_FOUND;
        }
        LocalDate birthDate = parseBirthDate(child);
        if (birthDate == null) {
            // 生年月日が解決できない子は安全側で封印（症状を隠さず記録）。
            log.warn("後見切替検証: 子 userId={} の birthDate が解決できず安全側で封印", childUserId);
            return SwitchVerdict.AGE_LOCKED;
        }
        GuardianshipAgePolicy policy = agePolicyRegistry.forCountry(child.getCountryCode());
        AgeStageResolution resolution = policy.resolve(birthDate, clock);
        return resolution.switchAllowed() ? SwitchVerdict.ALLOWED : SwitchVerdict.AGE_LOCKED;
    }

    /**
     * 子の自立移行ステータスを返す（02_api_design §2.3）。
     *
     * <p>保護者が「子がいつ自立段階に入るか（封印境界日）」「引き継ぎ（パスワード設定）が済んでいるか」を
     * 把握するための情報を返す。封印済み（{@code switchAllowed=false}）の子も {@code AGE_LOCKED} で
     * 例外にせず、現在段階・境界日・パスワード設定有無を返す（切替は不可でも状況把握は必要）。</p>
     *
     * <p><b>IDOR 防止</b>: 呼び出し元（保護者）が当該子の有効な保護者でない場合は
     * {@link MembershipBillingErrorCode#GUARDIANSHIP_LINK_NOT_FOUND}（403）。他人の子の状態は一切返さない。
     * 生年月日が解決できない子は安全側で {@code switchAllowed=false}・{@code stageKey="independent"} とし、
     * 境界日は {@code null} で返す（症状を隠さず記録）。</p>
     *
     * @param guardianUserId 保護者（認証ユーザー）のユーザーID
     * @param childUserId    対象の子のユーザーID
     * @return 自立移行ステータス
     * @throws BusinessException 有効な保護者リンクがない（{@code GUARDIANSHIP_LINK_NOT_FOUND} 403・IDOR）
     */
    public com.mannschaft.app.auth.dto.IndependenceStatusResponse getIndependenceStatus(
            Long guardianUserId, Long childUserId) {
        // 保護者リンク検証（IDOR 防止）。リンクなし／他人の子は 403。
        boolean linked = guardianUserId != null && childUserId != null
                && (parentalConsentService.isApprovedGuardian(guardianUserId, childUserId)
                || careLinkService.isActiveParentWatcher(guardianUserId, childUserId));
        if (!linked) {
            log.warn("自立移行ステータス取得拒否: 有効な保護者リンクなし guardianUserId={}, childUserId={}",
                    guardianUserId, childUserId);
            throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
        }

        UserEntity child = userRepository.findById(childUserId).orElse(null);
        if (child == null) {
            // リンクはあるが子が存在しない不整合 → IDOR と同様に 403（情報を漏らさない）。
            log.warn("自立移行ステータス取得拒否: 子ユーザー不在 childUserId={}", childUserId);
            throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
        }

        boolean passwordSet = child.getPasswordHash() != null && !child.getPasswordHash().isBlank();
        LocalDate birthDate = parseBirthDate(child);
        if (birthDate == null) {
            // 生年月日が解決できない子は安全側で封印扱い（境界日は算出不能ゆえ null）。
            log.warn("自立移行ステータス: 子 userId={} の birthDate 解決不能のため安全側（封印扱い）", childUserId);
            return new com.mannschaft.app.auth.dto.IndependenceStatusResponse(
                    childUserId, "independent", false, null, passwordSet);
        }

        GuardianshipAgePolicy policy = agePolicyRegistry.forCountry(child.getCountryCode());
        AgeStageResolution resolution = policy.resolve(birthDate, clock);
        LocalDate sealDate = policy.sealDate(birthDate, clock);
        return new com.mannschaft.app.auth.dto.IndependenceStatusResponse(
                childUserId, resolution.stageKey(), resolution.switchAllowed(), sealDate, passwordSet);
    }

    /**
     * 後見切替セッションを開始する（02_api_design §2.2 / 03_security §3.2）。
     *
     * <p>サーバ側ステートレス（セッションテーブルを持たない）。本メソッドは
     * (a) 保護者リンク有効性、(b) 年齢ゲート（{@code switchAllowed}）を検証し、成功時に
     * 監査を二重記録するのみ。以降クライアントが {@code X-Proxy-For-User-Id=childUserId} を保持し、
     * 毎リクエストを {@link com.mannschaft.app.proxy.ProxyInputContextFilter} の後見切替拡張が再検証する。</p>
     *
     * <p>検証順は「リンク → 年齢」。リンクなし（または他人の子へのなりすまし）は
     * {@link MembershipBillingErrorCode#GUARDIANSHIP_LINK_NOT_FOUND}（403）、
     * 封印段階は {@link MembershipBillingErrorCode#GUARDIANSHIP_SWITCH_AGE_LOCKED}（403）。</p>
     *
     * @param guardianUserId 保護者（acting-as する側＝認証ユーザー）のユーザーID
     * @param childUserId    切替対象の子のユーザーID
     * @throws BusinessException リンクなし / 年齢封印
     */
    @Transactional
    public void startSwitch(Long guardianUserId, Long childUserId) {
        // リンク → 年齢の順で副作用なし検証（毎回実行時評価・キャッシュしない）。
        SwitchVerdict verdict = evaluateSwitch(guardianUserId, childUserId);
        switch (verdict) {
            case LINK_NOT_FOUND -> {
                log.warn("後見切替開始拒否: 有効な保護者リンクなし guardianUserId={}, childUserId={}",
                        guardianUserId, childUserId);
                throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
            }
            case AGE_LOCKED -> {
                log.warn("後見切替開始拒否: 年齢到達で封印 guardianUserId={}, childUserId={}",
                        guardianUserId, childUserId);
                throw new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_SWITCH_AGE_LOCKED);
            }
            case ALLOWED -> {
                // 監査の二重記録（audit_logs（センシティブ）＋ proxy_input_records）。
                recordAudit(AuditEventType.GUARDIANSHIP_SWITCH_STARTED, guardianUserId, childUserId, null);
                saveProxyInputRecord(guardianUserId, childUserId);
                log.info("後見切替開始: guardianUserId={} → childUserId={}", guardianUserId, childUserId);
            }
        }
    }

    /**
     * 後見切替セッションを終了する（本人へ復帰・02_api_design §2.2）。
     *
     * <p>サーバ側ステートレスのため終了は監査記録のみ（解除すべきサーバ状態はない・クライアントが
     * {@code X-Proxy-For-User-Id} ヘッダの付与を止める）。再認可は行わない（既に封印されていても
     * 「終了」操作自体は妨げない＝安全側）。</p>
     *
     * @param guardianUserId 保護者（認証ユーザー）のユーザーID
     * @param childUserId    切替を終了する子のユーザーID
     */
    @Transactional
    public void endSwitch(Long guardianUserId, Long childUserId) {
        recordAudit(AuditEventType.GUARDIANSHIP_SWITCH_ENDED, guardianUserId, childUserId, null);
        log.info("後見切替終了: guardianUserId={} → childUserId={}", guardianUserId, childUserId);
    }

    /**
     * audit_logs へ後見切替の開始/終了を記録する（センシティブ・人間可読）。
     * userId=保護者・targetUserId=子。失敗してもメイン処理を止めない（AuditLogService 内で握る）。
     */
    private void recordAudit(AuditEventType eventType, Long guardianUserId, Long childUserId, String stageKey) {
        StringBuilder metadata = new StringBuilder("{\"childUserId\":").append(childUserId);
        if (stageKey != null) {
            metadata.append(",\"stageKey\":\"").append(stageKey).append('"');
        }
        metadata.append('}');
        auditLogService.record(
                eventType.name(),
                guardianUserId,   // userId: 操作者＝保護者
                childUserId,      // targetUserId: 対象＝子
                null, null,       // teamId / organizationId
                null, null,       // ipAddress / userAgent（Service 層からは取得しない）
                null,             // sessionHash
                metadata.toString());
    }

    /**
     * proxy_input_records へ後見切替の開始を追記する（03_security §3.2 二重記録）。
     *
     * <p>後見切替は紙の同意書を伴わないため {@code proxyInputConsentId=null}（V74.010 で NULLABLE 化）、
     * {@code inputSource=GUARDIANSHIP_SWITCH}。集計分離専用テーブルゆえ最小列のみ。
     * featureScope は切替中に許可される {@code PAYMENT}、targetEntity は切替対象の子。</p>
     */
    private void saveProxyInputRecord(Long guardianUserId, Long childUserId) {
        proxyInputRecordRepository.save(ProxyInputRecordEntity.builder()
                .proxyInputConsentId(null)
                .subjectUserId(childUserId)
                .proxyUserId(guardianUserId)
                .featureScope("PAYMENT")
                .targetEntityType(SWITCH_TARGET_ENTITY_TYPE)
                .targetEntityId(childUserId)
                .inputSource(ProxyInputRecordEntity.InputSource.GUARDIANSHIP_SWITCH)
                .originalStorageLocation(SWITCH_STORAGE_LOCATION_NA)
                .build());
    }

    /**
     * 子の暗号化 birthDate（復号済み文字列・ISO-8601）を {@link LocalDate} へパースする。
     * 値が無い／不正フォーマットの場合は {@code null} を返す（呼び出し側で安全側に封印）。
     */
    private LocalDate parseBirthDate(UserEntity child) {
        String raw = child.getBirthDate();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.warn("後見切替: 子 userId={} の birthDate パースに失敗（不正フォーマット）", child.getId());
            return null;
        }
    }
}
