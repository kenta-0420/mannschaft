package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.dto.RecruitmentCategoryResponse;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F03.11 募集型予約: カテゴリマスタサービス。
 * 設計書 §9.7 — 一覧取得のみ提供 (CRUD は不要、固定マスタのため)。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentCategoryService {

    private final RecruitmentCategoryRepository categoryRepository;
    private final RecruitmentMapper mapper;

    /** 全アクティブカテゴリを表示順で取得する。 */
    public List<RecruitmentCategoryResponse> listCategories() {
        return mapper.toCategoryResponseList(
                categoryRepository.findAllByIsActiveTrueOrderByDisplayOrderAsc());
    }
}
