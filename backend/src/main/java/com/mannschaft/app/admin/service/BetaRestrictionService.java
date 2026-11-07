package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.dto.BetaRestrictionConfigResponse;
import com.mannschaft.app.admin.dto.UpdateBetaRestrictionRequest;
import com.mannschaft.app.admin.entity.BetaRestrictionConfigEntity;
import com.mannschaft.app.admin.repository.BetaRestrictionConfigRepository;
import com.mannschaft.app.role.dto.BetaInviteScope;
import com.mannschaft.app.role.service.InviteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ベータ登録制限サービス。
 * ベータテスト期間中の新規登録を招待トークン保有者に限定する設定を管理する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BetaRestrictionService {

    private final BetaRestrictionConfigRepository repo;
    /** 他ドメイン（role）のトークンは Service 経由で引く（Repository / Entity を直接参照しない）。 */
    private final InviteService inviteService;

    /**
     * 設定を取得する。レコードが存在しない場合はデフォルト値（制限なし）で返す。
     *
     * @return ベータ制限設定レスポンス
     */
    public BetaRestrictionConfigResponse getConfig() {
        return repo.findTopByOrderByIdAsc()
                .map(entity -> new BetaRestrictionConfigResponse(
                        entity.getIsEnabled(),
                        entity.getMaxTeamId(),
                        entity.getMaxOrgId(),
                        entity.getUpdatedAt()))
                .orElseGet(() -> new BetaRestrictionConfigResponse(false, null, null, LocalDateTime.now()));
    }

    /**
     * 設定を更新する。
     *
     * @param req       更新リクエスト
     * @param updatedBy 更新者ID
     */
    @Transactional
    public void updateConfig(UpdateBetaRestrictionRequest req, Long updatedBy) {
        BetaRestrictionConfigEntity entity = repo.findTopByOrderByIdAsc()
                .orElseGet(() -> {
                    BetaRestrictionConfigEntity newEntity = BetaRestrictionConfigEntity.builder()
                            .updatedAt(LocalDateTime.now())
                            .build();
                    return repo.save(newEntity);
                });

        entity.update(req.getIsEnabled(), req.getMaxTeamId(), req.getMaxOrgId(), updatedBy);
        log.info("ベータ制限設定更新: isEnabled={}, maxTeamId={}, maxOrgId={}, updatedBy={}",
                req.getIsEnabled(), req.getMaxTeamId(), req.getMaxOrgId(), updatedBy);
    }

    /**
     * ベータ制限が有効かどうかを返す。
     *
     * @return ベータ制限が有効であれば true
     */
    public boolean isEnabled() {
        Boolean enabled = getConfig().getIsEnabled();
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 指定トークンがベータ招待として有効かどうかを検証する。
     * トークンが存在し、有効期限内で、かつ maxTeamId/maxOrgId の制限を満たすかチェックする。
     *
     * @param tokenStr 招待トークン文字列
     * @return 有効であれば true
     */
    public boolean isBetaTokenValid(String tokenStr) {
        Optional<BetaInviteScope> scopeOpt = inviteService.findValidInviteScope(tokenStr);
        if (scopeOpt.isEmpty()) {
            return false;
        }

        BetaInviteScope token = scopeOpt.get();

        // teamId・orgId が両方未設定のトークンは招待として無効扱い
        if (token.teamId() == null && token.organizationId() == null) {
            return false;
        }

        BetaRestrictionConfigResponse config = getConfig();

        // チームID制限チェック
        if (token.teamId() != null && config.getMaxTeamId() != null) {
            if (token.teamId() > config.getMaxTeamId()) {
                return false;
            }
        }

        // 組織ID制限チェック
        if (token.organizationId() != null && config.getMaxOrgId() != null) {
            if (token.organizationId() > config.getMaxOrgId()) {
                return false;
            }
        }

        return true;
    }
}
