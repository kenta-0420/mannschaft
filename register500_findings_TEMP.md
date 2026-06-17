# register 500 (COMMON_999) 根本原因調査メモ

## 再現（実機 :8080）
- nickname を省略したペイロード → 500 / COMMON_999
- nickname を含むペイロード → 201（成功）
- 差分は nickname のみ。これが決定的証拠。

## 根本原因
AuthRegistrationService.register() L139:
  .displayName(req.getNickname())
- RegisterRequest.nickname は任意（@Size(max=50)のみ、@NotBlank無し）
- UserEntity.displayName は @Column(nullable=false, length=50)
→ nickname 未指定時 displayName=NULL → NOT NULL 制約違反 → DataIntegrityViolationException → COMMON_999

## 根治方針
nickname が空/null の場合は氏名（lastName + firstName）から displayName を補完する。
DTO は任意のまま、サービス層で nullable=false 列を必ず埋める。
