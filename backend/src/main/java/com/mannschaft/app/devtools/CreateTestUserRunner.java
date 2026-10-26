package com.mannschaft.app.devtools;

import com.mannschaft.app.common.EncryptionService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * ローカル開発用テストユーザー作成。local プロファイル + create-test-user プロファイルの両方が
 * アクティブな場合のみ実行される。
 */
@Component
@Profile("create-test-user")
public class CreateTestUserRunner implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final EncryptionService encryptionService;

    public CreateTestUserRunner(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, EncryptionService encryptionService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.encryptionService = encryptionService;
    }

    @Override
    public void run(String... args) {
        String email = "test@example.com";

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ? AND deleted_at IS NULL", Integer.class, email);

        if (count != null && count > 0) {
            jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE email = ? AND deleted_at IS NULL", email);
            System.out.println("=== Test user already exists. Status updated to ACTIVE. ===");
        } else {
            String passwordHash = passwordEncoder.encode("Test1234!");
            String encLastName = encryptionService.encrypt("テスト");
            String encFirstName = encryptionService.encrypt("太郎");
            String lastNameHash = encryptionService.hmac("テスト");
            String firstNameHash = encryptionService.hmac("太郎");
            LocalDateTime now = LocalDateTime.now();

            jdbc.update(
                    "INSERT INTO users (email, password_hash, last_name, first_name, display_name, " +
                            "last_name_hash, first_name_hash, status, locale, timezone, " +
                            "is_searchable, reporting_restricted, encryption_key_version, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'ja', 'Asia/Tokyo', 1, 0, 1, ?, ?)",
                    email, passwordHash, encLastName, encFirstName, "テスト太郎",
                    lastNameHash, firstNameHash, now, now);
            System.out.println("=== Test user created! ===");
        }
        System.out.println("  Email:    test@example.com");
        System.out.println("  Password: Test1234!");
    }
}
