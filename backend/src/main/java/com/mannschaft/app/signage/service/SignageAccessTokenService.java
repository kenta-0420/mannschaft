package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.signage.SignageErrorCode;
import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.repository.SignageAccessTokenRepository;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * デジタルサイネージ アクセストークン管理サービス。
 * トークンの発行・一覧・無効化・検証を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SignageAccessTokenService {

    private final SignageAccessTokenRepository tokenRepository;
    private final SignageScreenRepository screenRepository;

    // ========================================
    // DTO 定義
    // ========================================

    /**
     * トークン発行リクエスト DTO。
     */
    public record IssueSignageTokenRequest(
            String name,
            /** 許可IPアドレスリスト（CIDR表記可。nullの場合は全許可） */
            List<String> allowedIps,
            /** トークン有効期限（nullの場合は無期限） */
            LocalDateTime expiredAt
    ) {}

    /**
     * トークンレスポンス DTO。
     */
    public record SignageAccessTokenResponse(
            Long id,
            Long screenId,
            String token,
            String name,
            Boolean isActive,
            List<String> allowedIps,
            LocalDateTime expiredAt,
            LocalDateTime createdAt
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * アクセストークンを発行する。
     * UUID.randomUUID() でトークン文字列を生成し、DBに永続化する。
     *
     * @param screenId  画面ID
     * @param createdBy 発行者ユーザーID
     * @param req       発行リクエスト
     * @return 発行したトークンレスポンス
     */
    @Transactional
    public SignageAccessTokenResponse issueToken(Long screenId, Long createdBy, IssueSignageTokenRequest req) {
        // 画面の存在確認
        screenRepository.findByIdAndDeletedAtIsNull(screenId)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_001));

        // UUID v4 トークン生成
        String token = UUID.randomUUID().toString();

        // 許可IPリストをJSON文字列に変換（簡易実装: JSON配列形式）
        String allowedIpsJson = buildAllowedIpsJson(req.allowedIps());

        SignageAccessTokenEntity entity = SignageAccessTokenEntity.builder()
                .screenId(screenId)
                .token(token)
                .name(req.name())
                .createdBy(createdBy)
                .allowedIps(allowedIpsJson)
                .build();

        SignageAccessTokenEntity saved = tokenRepository.save(entity);
        log.info("サイネージトークン発行: id={}, screenId={}, name={}", saved.getId(), screenId, req.name());
        return toResponse(saved, req.expiredAt());
    }

    /**
     * 画面に紐づくトークン一覧を取得する。
     *
     * @param screenId 画面ID
     * @return トークンレスポンス一覧
     */
    public List<SignageAccessTokenResponse> listTokens(Long screenId) {
        return tokenRepository.findByScreenId(screenId)
                .stream()
                .map(e -> toResponse(e, null))
                .toList();
    }

    /**
     * トークンを論理無効化する（isActive=false に更新）。
     *
     * @param id トークンID
     */
    @Transactional
    public void revokeToken(Long id) {
        SignageAccessTokenEntity entity = findTokenOrThrow(id);
        entity.deactivate();
        tokenRepository.save(entity);
        log.info("サイネージトークン無効化: id={}", id);
    }

    /**
     * トークン文字列を検証し、有効なトークンエンティティを返す。
     * 存在しない・無効・期限切れの場合は SIGNAGE_002 例外をスローする。
     *
     * @param token トークン文字列
     * @return 有効なトークンエンティティ
     */
    public SignageAccessTokenEntity validateToken(String token) {
        // isActive=true のトークンを取得
        SignageAccessTokenEntity entity = tokenRepository.findByTokenAndIsActiveTrue(token)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_002));

        log.debug("サイネージトークン検証成功: id={}", entity.getId());
        return entity;
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDでトークンを取得する。見つからない場合は SIGNAGE_005 例外をスローする。
     */
    private SignageAccessTokenEntity findTokenOrThrow(Long id) {
        return tokenRepository.findById(id)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_005));
    }

    /**
     * 許可IPリストをJSON配列文字列に変換する。
     * nullまたは空リストの場合はnullを返す（全許可）。
     */
    private String buildAllowedIpsJson(List<String> allowedIps) {
        if (allowedIps == null || allowedIps.isEmpty()) {
            return null;
        }
        // 簡易JSON配列生成（Jackson非依存）
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < allowedIps.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(allowedIps.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     * expiredAt は Entity に格納されていないため、引数から補完する。
     */
    private SignageAccessTokenResponse toResponse(SignageAccessTokenEntity e, LocalDateTime expiredAt) {
        // allowedIps JSON文字列をリストに変換（簡易実装）
        List<String> ips = parseAllowedIps(e.getAllowedIps());
        return new SignageAccessTokenResponse(
                e.getId(),
                e.getScreenId(),
                e.getToken(),
                e.getName(),
                e.getIsActive(),
                ips,
                expiredAt,
                e.getCreatedAt()
        );
    }

    /**
     * JSON配列文字列を String リストに変換する（簡易実装）。
     */
    private List<String> parseAllowedIps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        // 簡易パース: ["ip1","ip2"] → ["ip1", "ip2"]
        String trimmed = json.trim();
        if (trimmed.equals("[]")) {
            return List.of();
        }
        // ブラケットを除去してカンマ分割
        String inner = trimmed.substring(1, trimmed.length() - 1);
        return java.util.Arrays.stream(inner.split(","))
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
