package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.signage.SignageErrorCode;
import com.mannschaft.app.signage.SignageLayout;
import com.mannschaft.app.signage.SignageTransitionEffect;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * デジタルサイネージ 画面管理サービス。
 * 画面の作成・取得・更新・論理削除を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SignageScreenService {

    /** スコープ内の画面数上限 */
    private static final int MAX_SCREENS_PER_SCOPE = 10;

    private final SignageScreenRepository screenRepository;

    // ========================================
    // DTO 定義
    // ========================================

    /**
     * 画面作成リクエスト DTO。
     */
    public record CreateSignageScreenRequest(
            String scopeType,
            Long scopeId,
            String name,
            String description,
            SignageLayout layout,
            Integer defaultSlideDuration,
            SignageTransitionEffect transitionEffect
    ) {}

    /**
     * 画面更新リクエスト DTO。
     */
    public record UpdateSignageScreenRequest(
            String name,
            String description,
            SignageLayout layout,
            Integer defaultSlideDuration,
            SignageTransitionEffect transitionEffect,
            Boolean isActive
    ) {}

    /**
     * 画面レスポンス DTO。
     */
    public record SignageScreenResponse(
            Long id,
            String scopeType,
            Long scopeId,
            String name,
            String description,
            SignageLayout layout,
            Integer defaultSlideDuration,
            SignageTransitionEffect transitionEffect,
            Boolean isActive,
            java.time.LocalDateTime createdAt
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * 画面を作成する。スコープ内10画面制限あり。
     *
     * @param createdBy 作成者ユーザーID
     * @param req       作成リクエスト
     * @return 作成した画面レスポンス
     */
    @Transactional
    public SignageScreenResponse createScreen(Long createdBy, CreateSignageScreenRequest req) {
        // スコープ内10画面制限チェック
        List<SignageScreenEntity> existing = screenRepository
                .findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(req.scopeType(), req.scopeId());
        if (existing.size() >= MAX_SCREENS_PER_SCOPE) {
            throw new BusinessException(SignageErrorCode.SIGNAGE_001);
        }

        SignageScreenEntity entity = SignageScreenEntity.builder()
                .scopeType(req.scopeType())
                .scopeId(req.scopeId())
                .name(req.name())
                .layout(req.layout() != null ? req.layout() : SignageLayout.LANDSCAPE)
                .defaultSlideDuration(req.defaultSlideDuration() != null ? req.defaultSlideDuration() : 10)
                .transitionEffect(req.transitionEffect() != null ? req.transitionEffect() : SignageTransitionEffect.FADE)
                .createdBy(createdBy)
                .build();

        SignageScreenEntity saved = screenRepository.save(entity);
        log.info("サイネージ画面作成: id={}, scope={}/{}, name={}", saved.getId(), req.scopeType(), req.scopeId(), req.name());
        return toResponse(saved);
    }

    /**
     * 指定IDの画面を取得する。
     *
     * @param id 画面ID
     * @return 画面レスポンス
     */
    public SignageScreenResponse getScreen(Long id) {
        SignageScreenEntity entity = findScreenOrThrow(id);
        return toResponse(entity);
    }

    /**
     * スコープに紐づくアクティブな画面一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 画面レスポンス一覧
     */
    public List<SignageScreenResponse> listScreens(String scopeType, Long scopeId) {
        return screenRepository
                .findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(scopeType, scopeId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 画面を更新する。
     *
     * @param id  画面ID
     * @param req 更新リクエスト
     * @return 更新後画面レスポンス
     */
    @Transactional
    public SignageScreenResponse updateScreen(Long id, UpdateSignageScreenRequest req) {
        SignageScreenEntity entity = findScreenOrThrow(id);

        // toBuilder で更新対象フィールドを差し替える
        SignageScreenEntity updated = entity.toBuilder()
                .name(req.name() != null ? req.name() : entity.getName())
                .layout(req.layout() != null ? req.layout() : entity.getLayout())
                .defaultSlideDuration(req.defaultSlideDuration() != null ? req.defaultSlideDuration() : entity.getDefaultSlideDuration())
                .transitionEffect(req.transitionEffect() != null ? req.transitionEffect() : entity.getTransitionEffect())
                .isActive(req.isActive() != null ? req.isActive() : entity.getIsActive())
                .build();

        SignageScreenEntity saved = screenRepository.save(updated);
        log.info("サイネージ画面更新: id={}", id);
        return toResponse(saved);
    }

    /**
     * 画面を論理削除する。
     *
     * @param id 画面ID
     */
    @Transactional
    public void deleteScreen(Long id) {
        SignageScreenEntity entity = findScreenOrThrow(id);
        entity.softDelete();
        screenRepository.save(entity);
        log.info("サイネージ画面論理削除: id={}", id);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDで画面を取得する。見つからない場合は SIGNAGE_001 例外をスローする。
     */
    public SignageScreenEntity findScreenOrThrow(Long id) {
        return screenRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_001));
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     */
    private SignageScreenResponse toResponse(SignageScreenEntity e) {
        return new SignageScreenResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getName(),
                null, // description フィールドは Entity に存在しないためnull
                e.getLayout(),
                e.getDefaultSlideDuration(),
                e.getTransitionEffect(),
                e.getIsActive(),
                e.getCreatedAt()
        );
    }
}
