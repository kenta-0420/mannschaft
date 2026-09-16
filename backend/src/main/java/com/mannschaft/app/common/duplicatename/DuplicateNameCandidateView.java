package com.mannschaft.app.common.duplicatename;

/**
 * CMP-260901-1538 柱③-A 検分P1-1是正: クライアント応答（409 details）に載せる候補1件分。
 *
 * <p>{@link DuplicateNameCandidate} は候補の完全な情報（PRIVATE の id 含む）を保持し、
 * fingerprint 計算などサーバ内部の同一性判定にのみ用いる。一方、本クラスは
 * <b>PUBLIC（可視）候補のみ</b>を表し、クライアントへ実際に返す形。PRIVATE（チームは
 * PUBLIC 以外）の候補は id・slug 等の識別子を一切含めず
 * {@link DuplicateNameConfirmationDetails#hiddenCandidateCount()} の件数のみに集約する
 * （存在オラクル・ID 推測を防ぐ。検分 P1-1 是正）。</p>
 *
 * @param id   候補の組織/チーム ID（文字列化）。PUBLIC のみ存在するためここでは常に開示可
 * @param name 候補の名称
 */
public record DuplicateNameCandidateView(String id, String name) {
}
