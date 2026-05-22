package com.example.weatherapp.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class IdentityService {

	// 用户身份服务：同时支持匿名会话和注册登录用户。
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private final DatabaseClient databaseClient;

	public IdentityService(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Mono<UUID> createAnonymousUser(UUID sessionId) {
		// 匿名用户用随机 sessionId 作为外部标识，刷新页面后前端会从 localStorage 复用它。
		var subject = sessionId.toString();
		return databaseClient.sql("""
				insert into weather_app.app_users (identity_type, external_subject, display_name)
				values ('anonymous', :subject, '匿名用户')
				on conflict (identity_type, external_subject)
				do update set last_seen_at = now()
				returning id
				""")
				.bind("subject", subject)
				.map(row -> row.get("id", UUID.class))
				.one();
	}

	public Mono<AuthResponse> register(AuthRequest request) {
		// 注册流程：创建用户 -> 写入密码哈希 -> 创建登录会话。
		var email = normalizeEmail(request.email());
		var displayName = request.displayName() == null || request.displayName().isBlank() ? email : request.displayName().trim();
		var passwordHash = hashPassword(requiredPassword(request.password()));
		return databaseClient.sql("""
				with user_insert as (
				    insert into weather_app.app_users (identity_type, external_subject, display_name)
				    values ('registered', :email, :displayName)
				    returning id
				), credential_insert as (
				    insert into weather_app.registered_user_credentials (user_id, email, password_hash)
				    select id, :email, :passwordHash from user_insert
				    returning user_id
				)
				select user_id from credential_insert
				""")
				.bind("email", email)
				.bind("displayName", displayName)
				.bind("passwordHash", passwordHash)
				.map(row -> row.get("user_id", UUID.class))
				.one()
				.onErrorMap(error -> new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered"))
				.flatMap(userId -> createSession(userId).map(session -> new AuthResponse(userId, email, displayName, "registered", session.token(), session.expiresAt())));
	}

	public Mono<AuthResponse> login(AuthRequest request) {
		// 登录流程：查出密码哈希 -> 校验密码 -> 创建新的登录 token。
		var email = normalizeEmail(request.email());
		return databaseClient.sql("""
				select u.id, coalesce(u.display_name, c.email) as display_name, c.password_hash
				from weather_app.registered_user_credentials c
				join weather_app.app_users u on u.id = c.user_id
				where c.email = :email
				""")
				.bind("email", email)
				.map(row -> new CredentialRow(
						row.get("id", UUID.class),
						row.get("display_name", String.class),
						row.get("password_hash", String.class)
				))
				.one()
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")))
				.flatMap(row -> verifyPassword(request.password(), row.passwordHash())
						? createSession(row.userId()).map(session -> new AuthResponse(row.userId(), email, row.displayName(), "registered", session.token(), session.expiresAt()))
						: Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")));
	}

	public Mono<SessionResponse> currentSession(String token) {
		// token 只在前端保存明文，数据库里保存的是 SHA-256 哈希，降低泄露风险。
		var tokenHash = hashToken(requiredToken(token));
		return databaseClient.sql("""
				select u.id, c.email, coalesce(u.display_name, c.email) as display_name
				from weather_app.auth_sessions s
				join weather_app.app_users u on u.id = s.user_id
				join weather_app.registered_user_credentials c on c.user_id = u.id
				where s.token_hash = :tokenHash
				  and s.revoked_at is null
				  and s.expires_at > now()
				""")
				.bind("tokenHash", tokenHash)
				.map(row -> new SessionResponse(
						row.get("id", UUID.class),
						row.get("email", String.class),
						row.get("display_name", String.class),
						"registered"
				))
				.one()
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")));
	}

	public Mono<Void> logout(String token) {
		var tokenHash = hashToken(requiredToken(token));
		return databaseClient.sql("update weather_app.auth_sessions set revoked_at = now() where token_hash = :tokenHash")
				.bind("tokenHash", tokenHash)
				.then();
	}

	private Mono<SessionToken> createSession(UUID userId) {
		var token = randomToken();
		var expiresAt = Instant.now().plusSeconds(60L * 60 * 24 * 30);
		return databaseClient.sql("""
				insert into weather_app.auth_sessions (token_hash, user_id, expires_at)
				values (:tokenHash, :userId, :expiresAt)
				""")
				.bind("tokenHash", hashToken(token))
				.bind("userId", userId)
				.bind("expiresAt", expiresAt)
				.then()
				.thenReturn(new SessionToken(token, expiresAt));
	}

	private static String normalizeEmail(String email) {
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("Email is required");
		}
		return email.trim().toLowerCase();
	}

	private static String requiredPassword(String password) {
		if (password == null || password.length() < 6) {
			throw new IllegalArgumentException("Password must be at least 6 characters");
		}
		return password;
	}

	private static String requiredToken(String token) {
		if (token == null || token.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing token");
		}
		return token;
	}

	private static String hashPassword(String password) {
		try {
			// PBKDF2 会加盐并多轮计算，比直接存明文密码安全得多。
			var salt = new byte[16];
			SECURE_RANDOM.nextBytes(salt);
			KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
			var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
			return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(factory.generateSecret(spec).getEncoded());
		} catch (Exception error) {
			throw new IllegalStateException("Could not hash password", error);
		}
	}

	private static boolean verifyPassword(String password, String storedHash) {
		try {
			var parts = storedHash.split(":", 2);
			var salt = Base64.getDecoder().decode(parts[0]);
			var expected = Base64.getDecoder().decode(parts[1]);
			KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
			var actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
			return MessageDigest.isEqual(expected, actual);
		} catch (Exception error) {
			return false;
		}
	}

	private static String randomToken() {
		var bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String hashToken(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("Could not hash token", error);
		}
	}

	private record CredentialRow(UUID userId, String displayName, String passwordHash) {
	}

	private record SessionToken(String token, Instant expiresAt) {
	}
}
