package com.example.weatherapp.api;

import com.example.weatherapp.identity.AuthRequest;
import com.example.weatherapp.identity.AuthResponse;
import com.example.weatherapp.identity.IdentityService;
import com.example.weatherapp.identity.SessionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class AuthController {

	private final IdentityService identityService;

	public AuthController(IdentityService identityService) {
		this.identityService = identityService;
	}

	@PostMapping("/api/auth/register")
	Mono<AuthResponse> register(@RequestBody AuthRequest request) {
		// 注册成功后直接返回登录 token，前端不用再额外调用一次登录接口。
		return identityService.register(request);
	}

	@PostMapping("/api/auth/login")
	Mono<AuthResponse> login(@RequestBody AuthRequest request) {
		// 登录会校验邮箱和密码，成功后创建一条 auth_sessions 记录。
		return identityService.login(request);
	}

	@GetMapping("/api/auth/me")
	Mono<SessionResponse> me(@RequestHeader("X-Auth-Token") String token) {
		// 前端刷新页面时用这个接口恢复当前登录用户。
		return identityService.currentSession(token);
	}

	@PostMapping("/api/auth/logout")
	Mono<Void> logout(@RequestHeader("X-Auth-Token") String token) {
		return identityService.logout(token);
	}
}
