package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageInvitationAcceptResponse;
import com.mannschaft.app.village.dto.VillageInvitationCreateRequest;
import com.mannschaft.app.village.dto.VillageInvitationIssueResponse;
import com.mannschaft.app.village.dto.VillageInvitationSummary;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageInvitationEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageInvitationRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村招待サービス。非公開(UNLISTED)村の<b>存在秘匿を破らない入村導線</b>を提供する。
 *
 * <h2>最重要契約: 受諾の失敗はすべて「不在」に畳む</h2>
 * <p>受諾が失敗する理由（トークン不在／期限切れ／使用回数上限／失効済み／村が削除済み／
 * 村が凍結済み／指名型招待を指名外の者が使用）は、<b>すべて
 * {@link VillageErrorCode#VILLAGE_NOT_FOUND}（VILLAGE_001 / 404）という一つの応答へ畳む</b>。
 * 「招待が見つかりません」といった専用コードを作った瞬間、
 * <b>そのトークンが実在したかどうかが応答本文から判る</b>＝村の存在オラクルになる。
 * 呼び出し元にも理由を返さない（{@link #foldToAbsent()} 一本しか出口が無い）。</p>
 *
 * <h2>既存の不変条件は畳まない</h2>
 * <p>BAN(403) / 既に村人(409) / 参加上限(429) は、いずれも
 * <b>「その者が既に村と関係を持っている」ことが前提</b>であり、村の存在はその者にとって
 * 秘密ではない。よって従来どおりの応答を維持する（畳むと既存挙動の破壊になる）。</p>
 *
 * <h2>トークンの扱い（将来 SecretTokenVault へ差し替える）</h2>
 * <p>平文トークンは発行応答でのみ一度返し、DB には SHA-256 hex(64) のみを保存する。
 * ハッシュ方式は {@code auth/util/SecureTokenGenerator} および
 * {@code AuthTokenService#hashToken} と同一（SecureRandom 32 バイト → SHA-256 hex64）である。</p>
 * <p><b>なぜ共通部品を使わずここに私有実装を置いているか:</b> この方式を切り出した共通金庫
 * {@code common/token/SecretTokenVault} は別 PR（#2977）で審査中であり、本ブランチにまだ存在しない。
 * 同等物を本ブランチ内に「もう一つの共通部品」として作ると、着地後に共通部品が二重化して
 * どちらが正か分からなくなる。そこで<b>意図的に本サービスの private に閉じ込め</b>、
 * #2977 の着地後に {@code SecretTokenVault} 呼び出しへ置き換えられるようにしてある
 * （private であるため、他クラスが誤ってこちらへ依存することはない）。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VillageInvitationService {

    /** 1 ユーザーが参加できる村数のハード上限（{@code VillageMembershipService} と同値）。 */
    private static final int PARTICIPATION_HARD_LIMIT = 100;

    /** トークンの乱数バイト長。{@code SecureTokenGenerator} と揃える（変更禁止）。 */
    private static final int TOKEN_BYTE_LENGTH = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final VillageInvitationRepository invitationRepository;
    private final VillageMembershipRepository membershipRepository;
    private final VillageAccessGate villageAccessGate;

    // ======================================================================
    // 発行 / 一覧 / 失効（村長・長老のみ）
    // ======================================================================

    /** 招待を発行する（村長・長老のみ）。平文トークンはこの戻り値でのみ返す。 */
    @Transactional
    public VillageInvitationIssueResponse issue(
            UUID villageId, Long actorUserId, VillageInvitationCreateRequest request) {
        // 非公開村に非メンバーが触れた場合、ゲートが 404 に畳む（403 を返すと存在が漏れる）。
        villageAccessGate.loadActiveVillage(villageId, actorUserId);
        VillageMembershipEntity actor = requireHeadmanOrElder(villageId, actorUserId);

        String rawToken = generateRawToken();
        VillageInvitationEntity invitation = new VillageInvitationEntity();
        invitation.setVillageId(villageId);
        invitation.setTokenHash(hashToken(rawToken));
        invitation.setTargetUserId(request.targetUserId());
        invitation.setMaxUses(request.maxUses());
        invitation.setUsedCount(0);
        invitation.setExpiresAt(Instant.now().plus(request.expiresInHours(), ChronoUnit.HOURS));
        invitation.setCreatedByMembershipId(actor.getId());

        VillageInvitationEntity saved = invitationRepository.save(invitation);
        log.info("Village invitation issued: villageId={} membershipId={} maxUses={}",
                villageId, actor.getId(), saved.getMaxUses());

        // 平文トークンを返すのはこの一度きり。DB からは二度と復元できない。
        return new VillageInvitationIssueResponse(
                saved.getId(), rawToken, saved.getExpiresAt(),
                saved.getMaxUses(), saved.getTargetUserId());
    }

    /** 自村の招待一覧を返す（村長・長老のみ）。平文トークンは含めない。 */
    @Transactional(readOnly = true)
    public List<VillageInvitationSummary> list(UUID villageId, Long actorUserId) {
        villageAccessGate.loadActiveVillage(villageId, actorUserId);
        requireHeadmanOrElder(villageId, actorUserId);

        return invitationRepository.findByVillageId(villageId).stream()
                .map(inv -> new VillageInvitationSummary(
                        inv.getId(), inv.getExpiresAt(), inv.getMaxUses(),
                        inv.getUsedCount(), inv.getRevokedAt(), inv.getTargetUserId()))
                .toList();
    }

    /**
     * 招待を失効させる（村長・長老のみ／冪等）。
     *
     * <p>他村の招待 ID を渡された場合は 404 に畳む。「権限がありません(403)」を返すと
     * 「その ID の招待は実在する」＝その村は実在する、という手掛かりになる。</p>
     */
    @Transactional
    public void revoke(UUID villageId, UUID invitationId, Long actorUserId) {
        villageAccessGate.loadActiveVillage(villageId, actorUserId);
        requireHeadmanOrElder(villageId, actorUserId);

        VillageInvitationEntity invitation = invitationRepository.findById(invitationId)
                .filter(inv -> villageId.equals(inv.getVillageId()))
                .orElseThrow(VillageInvitationService::foldToAbsent);

        if (invitation.getRevokedAt() != null) {
            // 冪等: 既に失効済みなら何もしない。上書きすると「いつ失効したか」の履歴が壊れる。
            return;
        }
        invitation.setRevokedAt(Instant.now());
        invitationRepository.save(invitation);
    }

    // ======================================================================
    // 受諾（秘匿の本丸）
    // ======================================================================

    /**
     * 招待を受諾して村人になる。
     *
     * <p>村 ID を引数に取らない。トークンだけで村を解決することで、
     * 「その村が実在するか」を呼び出し側に一切明かさない。</p>
     *
     * <p>使用回数の増加とメンバーシップ作成は<b>同一トランザクション</b>に閉じる。
     * 行は {@code findByTokenHashForUpdate} の悲観ロックで直列化するため、
     * 同時受諾で {@code max_uses} を超えることはない。</p>
     */
    @Transactional
    public VillageInvitationAcceptResponse accept(String token, Long actorUserId) {
        VillageInvitationEntity invitation = resolveUsableInvitation(token, actorUserId);
        VillageEntity village = villageAccessGate.findVillageByCapability(invitation.getVillageId())
                .orElseThrow(VillageInvitationService::foldToAbsent);

        // ここから先は「既に村と関係を持つ者」への応答であり、存在は秘密ではない。従来の契約どおり返す。
        Optional<VillageMembershipEntity> existing = membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        village.getId(), VillageSubjectType.USER, actorUserId);
        if (existing.isPresent()) {
            if (existing.get().getBannedAt() != null) {
                throw new BusinessException(VillageErrorCode.MEMBER_BANNED);
            }
            throw new BusinessException(VillageErrorCode.ALREADY_MEMBER);
        }
        int activeCount = membershipRepository
                .findBySubjectTypeAndSubjectIdAndLeftAtIsNull(VillageSubjectType.USER, actorUserId)
                .size();
        if (activeCount >= PARTICIPATION_HARD_LIMIT) {
            throw new BusinessException(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED);
        }

        invitation.setUsedCount(invitation.getUsedCount() + 1);
        invitationRepository.save(invitation);

        VillageMembershipEntity created = membershipRepository.save(
                VillageMembershipEntity.builder()
                        .villageId(village.getId())
                        .subjectType(VillageSubjectType.USER)
                        .subjectId(actorUserId)
                        .role(VillageRole.VILLAGER)
                        .joinedAt(LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE))
                        // 「入村のきっかけとなった村人」。招待では発行者、参加申請では承認者が入る（両義）。
                        .invitedByMembershipId(invitation.getCreatedByMembershipId())
                        .build());

        log.info("Village invitation accepted: villageId={} membershipId={}",
                village.getId(), created.getId());
        return new VillageInvitationAcceptResponse(
                village.getId(), village.getName(), created.getId());
    }

    // ======================================================================
    // 内部
    // ======================================================================

    /**
     * トークンから利用可能な招待を解決する。<b>失敗理由は一切返さない。</b>
     *
     * <p>不在・期限切れ・上限到達・失効済み・指名外はすべて同一の例外に畳む。
     * 判定を呼び出し元へ boolean や enum で返す形にすると、その値がそのまま応答の分岐になり、
     * 秘匿が破れる。ここで例外に落とし切ることが構造上の担保である。</p>
     */
    private VillageInvitationEntity resolveUsableInvitation(String token, Long actorUserId) {
        if (token == null || token.isBlank()) {
            throw foldToAbsent();
        }
        VillageInvitationEntity invitation =
                invitationRepository.findByTokenHashForUpdate(hashToken(token))
                        .orElseThrow(VillageInvitationService::foldToAbsent);
        if (!invitation.isUsable()) {
            throw foldToAbsent();
        }
        // 指名型招待は指名された者だけが使える。指名外には「不在」と同じ顔を返す。
        if (invitation.getTargetUserId() != null
                && !invitation.getTargetUserId().equals(actorUserId)) {
            throw foldToAbsent();
        }
        return invitation;
    }

    /**
     * 受諾の失敗を「不在」へ畳む唯一の出口。
     *
     * <p>専用のエラーコードを新設してはならない（それ自体が存在の答えになる）。</p>
     */
    private static BusinessException foldToAbsent() {
        return new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    /**
     * 操作者が村長または長老の<b>現役</b>メンバーであることを要求する。
     *
     * <p>{@code findActiveByVillageIdAndSubject} を使う（{@code ...AndLeftAtIsNull} 止まりの版は
     * BAN を見ないため、BAN 済みの長老が操作を継続できてしまう）。</p>
     */
    private VillageMembershipEntity requireHeadmanOrElder(UUID villageId, Long actorUserId) {
        VillageMembershipEntity membership = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (membership.getRole() != VillageRole.HEADMAN && membership.getRole() != VillageRole.ELDER) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
        return membership;
    }

    /**
     * 平文トークンを生成する（SecureRandom 32 バイト → Base64URL・パディング無し）。
     *
     * <p>共通金庫 {@code SecretTokenVault}（PR #2977）着地後はそちらへ差し替えること。</p>
     */
    private static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * トークンを SHA-256 hex(64) にする（{@code AuthTokenService#hashToken} と同一方式）。
     *
     * <p>共通金庫 {@code SecretTokenVault}（PR #2977）着地後はそちらへ差し替えること。</p>
     */
    private static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 は全 JVM でサポート必須のため到達不能
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
