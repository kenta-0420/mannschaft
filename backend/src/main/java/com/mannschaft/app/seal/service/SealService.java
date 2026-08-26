package com.mannschaft.app.seal.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.seal.SealErrorCode;
import com.mannschaft.app.seal.SealMapper;
import com.mannschaft.app.seal.SealScopeType;
import com.mannschaft.app.seal.SealVariant;
import com.mannschaft.app.seal.dto.CreateSealRequest;
import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.SetScopeDefaultRequest;
import com.mannschaft.app.seal.dto.UpdateScopeDefaultsRequest;
import com.mannschaft.app.seal.dto.UpdateSealRequest;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.entity.SealScopeDefaultEntity;
import com.mannschaft.app.seal.repository.ElectronicSealRepository;
import com.mannschaft.app.seal.repository.SealScopeDefaultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 電子印鑑サービス。印鑑の生成・管理・スコープデフォルト設定を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SealService {

    private final ElectronicSealRepository sealRepository;
    private final SealScopeDefaultRepository scopeDefaultRepository;
    private final SealMapper sealMapper;
    private final SealGenerator sealGenerator;
    private final NameResolverService nameResolverService;

    /**
     * ユーザーの印鑑一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 印鑑レスポンスリスト
     */
    public List<SealResponse> listSeals(Long userId) {
        List<ElectronicSealEntity> seals = sealRepository.findByUserIdOrderByCreatedAtAsc(userId);
        return sealMapper.toSealResponseList(seals);
    }

    /**
     * 印鑑詳細を取得する。
     *
     * @param userId ユーザーID
     * @param sealId 印鑑ID
     * @return 印鑑レスポンス
     */
    public SealResponse getSeal(Long userId, Long sealId) {
        ElectronicSealEntity entity = findSealOrThrow(userId, sealId);
        return sealMapper.toSealResponse(entity);
    }

    /**
     * 印鑑を作成する。
     *
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成された印鑑レスポンス
     */
    @Transactional
    public SealResponse createSeal(Long userId, CreateSealRequest request) {
        SealVariant variant = SealVariant.valueOf(request.getVariant());

        if (sealRepository.existsByUserIdAndVariant(userId, variant)) {
            throw new BusinessException(SealErrorCode.DUPLICATE_VARIANT);
        }

        String svgData = sealGenerator.generateSvg(request.getDisplayText(), variant);
        String sealHash = sealGenerator.computeHash(svgData);

        // 論理削除済みの同一 (user_id, variant) 行が UNIQUE 制約上に残っている場合、
        // 通常の INSERT は DataIntegrityViolationException（→ COMMON_999/500）になる。
        // seal_stamp_logs が同一ドメイン内 FK(ON DELETE RESTRICT) で sealId を参照し続けている
        // 可能性があるため物理削除はせず、同じ行を「復活（undelete）+ 内容更新」する。
        // 詳細: ElectronicSealRepository#reviveDeleted の Javadoc 参照。
        int revivedCount = sealRepository.reviveDeleted(
                userId, variant.name(), request.getDisplayText(), svgData, sealHash);

        ElectronicSealEntity saved;
        if (revivedCount > 0) {
            saved = sealRepository.findByUserIdAndVariant(userId, variant)
                    .orElseThrow(() -> new BusinessException(SealErrorCode.SEAL_NOT_FOUND));
            log.info("印鑑作成（論理削除済み行を復活）: userId={}, sealId={}, variant={}", userId, saved.getId(), variant);
        } else {
            ElectronicSealEntity entity = ElectronicSealEntity.builder()
                    .userId(userId)
                    .variant(variant)
                    .displayText(request.getDisplayText())
                    .svgData(svgData)
                    .sealHash(sealHash)
                    .build();
            saved = sealRepository.save(entity);
            log.info("印鑑作成: userId={}, sealId={}, variant={}", userId, saved.getId(), variant);
        }
        return sealMapper.toSealResponse(saved);
    }

    /**
     * 印鑑を更新する（表示テキスト変更 → SVG再生成）。
     *
     * @param userId  ユーザーID
     * @param sealId  印鑑ID
     * @param request 更新リクエスト
     * @return 更新された印鑑レスポンス
     */
    @Transactional
    public SealResponse updateSeal(Long userId, Long sealId, UpdateSealRequest request) {
        ElectronicSealEntity entity = findSealOrThrow(userId, sealId);

        entity.updateDisplayText(request.getDisplayText());

        String newSvgData = sealGenerator.generateSvg(request.getDisplayText(), entity.getVariant());
        String newSealHash = sealGenerator.computeHash(newSvgData);
        entity.regenerate(newSvgData, newSealHash);

        ElectronicSealEntity saved = sealRepository.save(entity);
        log.info("印鑑更新: userId={}, sealId={}, version={}", userId, sealId, saved.getGenerationVersion());
        return sealMapper.toSealResponse(saved);
    }

    /**
     * 印鑑を論理削除する。
     *
     * @param userId ユーザーID
     * @param sealId 印鑑ID
     */
    @Transactional
    public void deleteSeal(Long userId, Long sealId) {
        ElectronicSealEntity entity = findSealOrThrow(userId, sealId);
        entity.softDelete();
        sealRepository.save(entity);

        // 関連するスコープデフォルトも削除
        scopeDefaultRepository.deleteBySealId(sealId);

        log.info("印鑑削除: userId={}, sealId={}", userId, sealId);
    }

    /**
     * ユーザーの全印鑑を再生成する（SVGデータの再構築）。
     * 印鑑が0件の場合はプロフィール氏名から3バリアント（姓・フルネーム・名）を初回生成する。
     *
     * @param userId ユーザーID
     * @return 再生成／生成後の印鑑レスポンスリスト
     */
    @Transactional
    public List<SealResponse> regenerateSeals(Long userId) {
        List<ElectronicSealEntity> seals = sealRepository.findByUserIdOrderByCreatedAtAsc(userId);

        if (seals.isEmpty()) {
            // 論理削除済みのレコードがユニーク制約 (user_id, variant) に引っかかるため、
            // 初回生成（INSERT）の前に物理削除しておく。
            // @SQLRestriction は SELECT にのみ適用され、ここでの findByUserId... には作用しない。
            int purged = sealRepository.deleteByUserIdAndDeletedAtIsNotNull(userId);
            if (purged > 0) {
                log.info("論理削除済み印鑑を物理削除: userId={}, count={}", userId, purged);
            }
            return initializeSeals(userId);
        }

        List<ElectronicSealEntity> updated = seals.stream()
                .map(seal -> {
                    String newSvgData = sealGenerator.generateSvg(seal.getDisplayText(), seal.getVariant());
                    String newSealHash = sealGenerator.computeHash(newSvgData);
                    seal.regenerate(newSvgData, newSealHash);
                    return sealRepository.save(seal);
                })
                .toList();
        log.info("印鑑一括再生成: userId={}, count={}", userId, updated.size());
        return sealMapper.toSealResponseList(updated);
    }

    /**
     * プロフィール氏名から3バリアントの印鑑を初回生成する。
     * 氏名が未設定のバリアントはスキップする。
     */
    private List<SealResponse> initializeSeals(Long userId) {
        NameResolverService.UserNameParts nameParts = nameResolverService.resolveUserNameParts(userId);
        String lastName = nameParts.lastName();
        String firstName = nameParts.firstName();

        List<ElectronicSealEntity> created = List.of(SealVariant.LAST_NAME, SealVariant.FULL_NAME, SealVariant.FIRST_NAME).stream()
                .map(variant -> {
                    String displayText = switch (variant) {
                        case LAST_NAME -> lastName;
                        case FULL_NAME -> lastName + firstName;
                        case FIRST_NAME -> firstName;
                    };
                    if (displayText.isBlank()) return null;
                    String svgData = sealGenerator.generateSvg(displayText, variant);
                    String sealHash = sealGenerator.computeHash(svgData);
                    return sealRepository.save(ElectronicSealEntity.builder()
                            .userId(userId)
                            .variant(variant)
                            .displayText(displayText)
                            .svgData(svgData)
                            .sealHash(sealHash)
                            .build());
                })
                .filter(e -> e != null)
                .toList();
        log.info("印鑑初回生成: userId={}, count={}", userId, created.size());
        return sealMapper.toSealResponseList(created);
    }

    /**
     * スコープデフォルトを設定する。
     *
     * @param userId  ユーザーID
     * @param request 設定リクエスト
     * @return スコープデフォルトレスポンス
     */
    @Transactional
    public ScopeDefaultResponse setScopeDefault(Long userId, SetScopeDefaultRequest request) {
        SealScopeType scopeType = SealScopeType.valueOf(request.getScopeType());

        // 印鑑の存在確認
        findSealOrThrow(userId, request.getSealId());

        // 既存のスコープデフォルトがあれば更新、なければ新規作成
        SealScopeDefaultEntity entity = scopeDefaultRepository
                .findByUserIdAndScopeTypeAndScopeId(userId, scopeType, request.getScopeId())
                .map(existing -> {
                    existing.changeSeal(request.getSealId());
                    return existing;
                })
                .orElseGet(() -> SealScopeDefaultEntity.builder()
                        .userId(userId)
                        .scopeType(scopeType)
                        .scopeId(request.getScopeId())
                        .sealId(request.getSealId())
                        .build());

        SealScopeDefaultEntity saved = scopeDefaultRepository.save(entity);
        log.info("スコープデフォルト設定: userId={}, scopeType={}, sealId={}", userId, scopeType, request.getSealId());

        // scopeName を解決して返す（単一件のため都度解決）
        Map<Long, String> teamNames = saved.getScopeType() == SealScopeType.TEAM && saved.getScopeId() != null
                ? nameResolverService.resolveTeamNames(Set.of(saved.getScopeId()))
                : Map.of();
        Map<Long, String> orgNames = saved.getScopeType() == SealScopeType.ORGANIZATION && saved.getScopeId() != null
                ? nameResolverService.resolveOrganizationNames(Set.of(saved.getScopeId()))
                : Map.of();
        // variant を同一 seal ドメイン内で解決（印鑑が削除済みの場合は null）
        SealVariant variant = sealRepository.findById(saved.getSealId())
                .map(ElectronicSealEntity::getVariant)
                .orElse(null);
        return sealMapper.toScopeDefaultResponse(saved, resolveScopeName(saved, teamNames, orgNames), variant);
    }

    /**
     * ユーザーのスコープデフォルトを一括更新する。
     * variant から sealId を自動解決し、スコープごとに upsert する。
     *
     * @param userId  ユーザーID
     * @param request 一括更新リクエスト
     * @return 更新後のスコープデフォルトレスポンスリスト
     */
    @Transactional
    public List<ScopeDefaultResponse> updateScopeDefaults(Long userId, UpdateScopeDefaultsRequest request) {
        for (UpdateScopeDefaultsRequest.ScopeDefaultItem item : request.getDefaults()) {
            SealVariant variant = SealVariant.valueOf(item.getVariant());
            SealScopeType scopeType = SealScopeType.valueOf(item.getScopeType());

            // variant から sealId を解決（印鑑が存在しない場合はそのアイテムをスキップ）
            Optional<ElectronicSealEntity> sealOpt = sealRepository.findByUserIdAndVariant(userId, variant);
            if (sealOpt.isEmpty()) {
                log.warn("スコープデフォルト更新スキップ: userId={}, variant={} の印鑑が存在しない", userId, variant);
                continue;
            }
            Long sealId = sealOpt.get().getId();

            SealScopeDefaultEntity entity = scopeDefaultRepository
                    .findByUserIdAndScopeTypeAndScopeId(userId, scopeType, item.getScopeId())
                    .map(existing -> {
                        existing.changeSeal(sealId);
                        return existing;
                    })
                    .orElseGet(() -> SealScopeDefaultEntity.builder()
                            .userId(userId)
                            .scopeType(scopeType)
                            .scopeId(item.getScopeId())
                            .sealId(sealId)
                            .build());

            scopeDefaultRepository.save(entity);
            log.info("スコープデフォルト更新: userId={}, scopeType={}, sealId={}", userId, scopeType, sealId);
        }

        return listScopeDefaults(userId);
    }

    /**
     * ユーザーのスコープデフォルト一覧を取得する。
     * scopeName（チーム名・組織名）および variant は N+1 を避けるため一括解決する。
     *
     * @param userId ユーザーID
     * @return スコープデフォルトレスポンスリスト
     */
    public List<ScopeDefaultResponse> listScopeDefaults(Long userId) {
        List<SealScopeDefaultEntity> defaults = scopeDefaultRepository.findByUserIdOrderByCreatedAtAsc(userId);

        // TEAM / ORGANIZATION の scopeId を種別ごとに集約し、それぞれ一括で名前解決する
        Set<Long> teamIds = defaults.stream()
                .filter(d -> d.getScopeType() == SealScopeType.TEAM && d.getScopeId() != null)
                .map(SealScopeDefaultEntity::getScopeId)
                .collect(Collectors.toSet());
        Set<Long> orgIds = defaults.stream()
                .filter(d -> d.getScopeType() == SealScopeType.ORGANIZATION && d.getScopeId() != null)
                .map(SealScopeDefaultEntity::getScopeId)
                .collect(Collectors.toSet());

        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamIds);
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgIds);

        // variant を sealId 単位で一括解決する（N+1 回避）
        Set<Long> sealIds = defaults.stream()
                .map(SealScopeDefaultEntity::getSealId)
                .collect(Collectors.toSet());
        Map<Long, SealVariant> sealVariantMap = sealRepository.findAllById(sealIds).stream()
                .collect(Collectors.toMap(ElectronicSealEntity::getId, ElectronicSealEntity::getVariant));

        return defaults.stream()
                .map(d -> sealMapper.toScopeDefaultResponse(
                        d,
                        resolveScopeName(d, teamNames, orgNames),
                        sealVariantMap.get(d.getSealId())))
                .toList();
    }

    /**
     * スコープ表示名を解決する。
     * DEFAULT="デフォルト" / TEAM→チーム名（不明="不明なチーム"） /
     * ORGANIZATION→組織名（不明="不明な組織"）。
     */
    private String resolveScopeName(SealScopeDefaultEntity entity,
                                    Map<Long, String> teamNames,
                                    Map<Long, String> orgNames) {
        return switch (entity.getScopeType()) {
            case DEFAULT -> "デフォルト";
            case TEAM -> teamNames.getOrDefault(entity.getScopeId(), "不明なチーム");
            case ORGANIZATION -> orgNames.getOrDefault(entity.getScopeId(), "不明な組織");
        };
    }

    /**
     * 印鑑エンティティを取得する。他サービスからの参照用。
     *
     * @param sealId 印鑑ID
     * @return 印鑑エンティティ
     */
    public ElectronicSealEntity getSealEntity(Long sealId) {
        return sealRepository.findById(sealId)
                .orElseThrow(() -> new BusinessException(SealErrorCode.SEAL_NOT_FOUND));
    }

    /**
     * 印鑑を取得する。存在しない場合は例外をスローする。
     */
    private ElectronicSealEntity findSealOrThrow(Long userId, Long sealId) {
        return sealRepository.findByIdAndUserId(sealId, userId)
                .orElseThrow(() -> new BusinessException(SealErrorCode.SEAL_NOT_FOUND));
    }

}
