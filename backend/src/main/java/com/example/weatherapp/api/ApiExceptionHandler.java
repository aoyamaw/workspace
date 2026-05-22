package com.example.weatherapp.api;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception, ServerHttpRequest request) {
		// 业务代码主动抛出的 HTTP 错误会统一转换成 ApiError，前端更容易显示错误信息。
		var status = exception.getStatusCode();
		return ResponseEntity.status(status).body(new ApiError(
				Instant.now(),
				status.value(),
				status.toString(),
				exception.getReason(),
				request.getPath().value()
		));
	}

	@ExceptionHandler({IllegalArgumentException.class, WebExchangeBindException.class})
	ResponseEntity<ApiError> handleValidation(Exception exception, ServerHttpRequest request) {
		// 参数缺失或格式错误统一返回 400，避免每个 Controller 重复写错误处理。
		return ResponseEntity.badRequest().body(new ApiError(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				HttpStatus.BAD_REQUEST.getReasonPhrase(),
				exception.getMessage(),
				request.getPath().value()
		));
	}
}
