package com.mannschaft.app.seal.service;

import com.mannschaft.app.seal.SealMapper;
import com.mannschaft.app.seal.dto.AdminRegenerateResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.repository.ElectronicSealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 電子印鑑管理者サービス。SYSTEM_ADMIN向けの一括操作を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SealAdminService {

    private final ElectronicSealRepository sealRepository;
    private final SealMapper sealMapper;

    /**
     * 全印鑑一覧を取得する（管理者用）。
     *
     * @return 全印鑑レスポンスリスト
     */
    public List<SealResponse> listAllSeals() {
        List<ElectronicSealEntity> seals = sealRepository.findAllByOrderByUserIdAsc();
        return sealMapper.toSealResponseList(seals);
    }

    /**
     * 全印鑑のSVGを一括再生成する。
     *
     * @return 再生成結果レスポンス
     */
    @Transactional
    public AdminRegenerateResponse regenerateAll() {
        List<ElectronicSealEntity> seals = sealRepository.findAllByOrderByUserIdAsc();

        long successCount = 0;
        long failureCount = 0;

        for (ElectronicSealEntity seal : seals) {
            try {
                // TODO: 実際のSVG再生成ロジックに置き換える
                String newSvgData = generateSvgPlaceholder(seal.getDisplayText());
                // TODO: 実際のSHA-256ハッシュ再生成に置き換える
                String newSealHash = generateHashPlaceholder(newSvgData);
                seal.regenerate(newSvgData, newSealHash);
                sealRepository.save(seal);
                successCount++;
            } catch (Exception e) {
                log.error("印鑑再生成失敗: sealId={}, error={}", seal.getId(), e.getMessage());
                failureCount++;
            }
        }

        log.info("一括再生成完了: total={}, success={}, failure={}", seals.size(), successCount, failureCount);
        return new AdminRegenerateResponse((long) seals.size(), successCount, failureCount);
    }

    /**
     * SVG生成のプレースホルダー。
     * TODO: SealServiceと共通化し、実際のSVG生成ロジックに置き換える
     */
    private String generateSvgPlaceholder(String displayText) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">"
                + "<circle cx=\"50\" cy=\"50\" r=\"45\" fill=\"none\" stroke=\"red\" stroke-width=\"3\"/>"
                + "<text x=\"50\" y=\"55\" text-anchor=\"middle\" fill=\"red\" font-size=\"14\">"
                + displayText + "</text></svg>";
    }

    /**
     * SHA-256ハッシュ生成のプレースホルダー。
     * TODO: SealServiceと共通化し、実際のSHA-256ハッシュ生成に置き換える
     */
    private String generateHashPlaceholder(String data) {
        return String.format("%064x", data.hashCode());
    }
}
