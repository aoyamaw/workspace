package com.example.weatherapp.recommendations;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LocalRecommendationGenerator {

	public List<RecommendationSeed> generate(String displayName, int offset) {
		var region = displayName == null || displayName.isBlank() ? "当地" : displayName.trim();
		var foods = List.of(
				"招牌小吃", "本地汤面", "街区点心", "传统糕点", "时令家常菜",
				"夜市烤物", "手作甜品", "老店套餐", "特色早餐", "地方饮品",
				"经典蒸点", "风味拌面", "热锅菜", "节令小食", "市场熟食"
		);
		var places = List.of(
				"城市地标", "历史街区", "滨水步道", "公共博物馆", "本地公园",
				"观景平台", "文化街巷", "市集街区", "亲子场馆", "夜景路线",
				"艺术空间", "古建片区", "自然步道", "城市广场", "慢行街区"
		);
		var result = new ArrayList<RecommendationSeed>();
		for (int index = 0; index < foods.size(); index++) {
			var itemIndex = (index + offset) % foods.size();
			var name = region + foods.get(itemIndex);
			result.add(new RecommendationSeed(
					RecommendationCategory.food,
					name,
					"适合快速体验" + region + "风味的代表性选择，建议结合当天行程就近安排。",
					imageUrl(itemIndex),
					name + "插图",
					region + "公开餐饮资料",
					"https://www.mct.gov.cn/"
			));
		}
		for (int index = 0; index < places.size(); index++) {
			var itemIndex = (index + offset) % places.size();
			var name = region + places.get(itemIndex);
			result.add(new RecommendationSeed(
					RecommendationCategory.place,
					name,
					"适合加入" + region + "一日或半日路线，天气合适时可与周边餐饮组合安排。",
					imageUrl(itemIndex + 20),
					name + "插图",
					region + "公开旅游资料",
					"https://www.mct.gov.cn/"
			));
		}
		return result;
	}

	private static String imageUrl(int index) {
		var ids = List.of(
				"photo-1563245372-f21724e3856d", "photo-1496116218417-1a781b1c416c",
				"photo-1612929633738-8fe44f7ec841", "photo-1546069901-ba9599a7e63c",
				"photo-1606491956689-2ea866880c84", "photo-1548919973-5cef591cdbc9",
				"photo-1535078035266-a0fa7d3b8f65", "photo-1566127992631-137a642a90f4",
				"photo-1494526585095-c41746248156", "photo-1518005020951-eccb494ad742"
		);
		return "https://images.unsplash.com/" + ids.get(Math.floorMod(index, ids.size()))
				+ "?auto=format&fit=crop&w=640&q=80";
	}
}
