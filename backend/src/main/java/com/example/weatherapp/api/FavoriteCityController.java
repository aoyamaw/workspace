package com.example.weatherapp.api;

import com.example.weatherapp.favorites.FavoriteCityRequest;
import com.example.weatherapp.favorites.FavoriteCityResponse;
import com.example.weatherapp.favorites.FavoriteCityService;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class FavoriteCityController {

	private final FavoriteCityService favoriteCityService;

	public FavoriteCityController(FavoriteCityService favoriteCityService) {
		this.favoriteCityService = favoriteCityService;
	}

	@GetMapping("/api/favorites")
	Flux<FavoriteCityResponse> list(@RequestHeader("X-User-Id") UUID userId) {
		// 查询当前用户的收藏城市列表。
		return favoriteCityService.listFavorites(userId);
	}

	@PostMapping("/api/favorites")
	Mono<FavoriteCityResponse> save(@RequestHeader("X-User-Id") UUID userId, @RequestBody FavoriteCityRequest request) {
		// 保存当前城市到收藏；同一个用户重复收藏同一城市时会更新原记录。
		return favoriteCityService.saveFavorite(userId, request);
	}

	@DeleteMapping("/api/favorites/{favoriteId}")
	Mono<Void> delete(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID favoriteId) {
		// 删除收藏时同时校验 owner_id，防止删到别人的收藏。
		return favoriteCityService.deleteFavorite(userId, favoriteId);
	}
}
