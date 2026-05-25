package com.mannschaft.app.resume.repository;

import com.mannschaft.app.resume.entity.ResumeSkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 構造化スキル リポジトリ（F01.10）。
 *
 * <p>{@link ResumeSkillEntity} の {@code @SQLRestriction} により、
 * {@link #findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID)} は
 * 論理削除済みレコードを自動除外する。
 *
 * <p>一括保存（PUT /full）での子要素の全件差し替えや、
 * 複製（POST /copy）での全件コピーには
 * {@link #findByResumeId(UUID)} を使用すること。
 *
 * <p>本リポジトリが扱う「構造化スキル」は
 * {@link com.mannschaft.app.resume.entity.ResumeEntity#getSkillsSummary()} の
 * 散文テキストとは異なる。両者は役割が補完的であり併存する設計である。
 */
public interface ResumeSkillRepository extends JpaRepository<ResumeSkillEntity, UUID> {

    /**
     * 生存中のスキルを表示順で取得する。
     */
    List<ResumeSkillEntity> findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID resumeId);

    /**
     * 全件（論理削除含む）を resume_id で取得する。
     *
     * <p>履歴書の複製処理（Service 層）で使用する。
     */
    List<ResumeSkillEntity> findByResumeId(UUID resumeId);
}
