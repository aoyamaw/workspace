package com.example.weatherapp.weather;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CitySuggestionIndex {

	// 本地城市索引：用于毕业项目演示时快速匹配常用城市，也能避免每次输入都调用外部地理编码接口。
	private final List<IndexedCity> cities = List.of(
			new IndexedCity("shanghai-cn", "上海", "上海, 上海市, 中国", "中国", "CN", "上海市", "Asia/Shanghai", 31.2304, 121.4737, List.of("shanghai", "上海", "沪")),
			new IndexedCity("pudong-shanghai-cn", "浦东", "浦东, 上海市, 中国", "中国", "CN", "上海市", "Asia/Shanghai", 31.2211, 121.5441, List.of("pudong", "浦东")),
			new IndexedCity("beijing-cn", "北京", "北京, 北京市, 中国", "中国", "CN", "北京市", "Asia/Shanghai", 39.9042, 116.4074, List.of("beijing", "北京", "京")),
			new IndexedCity("guangzhou-cn", "广州", "广州, 广东省, 中国", "中国", "CN", "广东省", "Asia/Shanghai", 23.1291, 113.2644, List.of("guangzhou", "广州")),
			new IndexedCity("shenzhen-cn", "深圳", "深圳, 广东省, 中国", "中国", "CN", "广东省", "Asia/Shanghai", 22.5431, 114.0579, List.of("shenzhen", "深圳")),
			new IndexedCity("hangzhou-cn", "杭州", "杭州, 浙江省, 中国", "中国", "CN", "浙江省", "Asia/Shanghai", 30.2741, 120.1551, List.of("hangzhou", "杭州")),
			new IndexedCity("nanjing-cn", "南京", "南京, 江苏省, 中国", "中国", "CN", "江苏省", "Asia/Shanghai", 32.0603, 118.7969, List.of("nanjing", "南京")),
			new IndexedCity("chengdu-cn", "成都", "成都, 四川省, 中国", "中国", "CN", "四川省", "Asia/Shanghai", 30.5728, 104.0668, List.of("chengdu", "成都")),
			new IndexedCity("new-york-us", "New York", "New York, New York, United States", "United States", "US", "New York", "America/New_York", 40.7128, -74.0060, List.of("new york", "nyc", "纽约")),
			new IndexedCity("london-gb", "London", "London, England, United Kingdom", "United Kingdom", "GB", "England", "Europe/London", 51.5072, -0.1276, List.of("london", "伦敦"))
	);

	public List<LocationCandidate> search(String query) {
		// 支持中文、英文、拼音和别名搜索，并按匹配程度排序。
		var normalized = normalize(query);
		if (normalized.isBlank()) {
			return List.of();
		}
		return cities.stream()
				.filter(city -> city.matches(normalized))
				.sorted(Comparator.comparingInt(city -> city.rank(normalized)))
				.limit(8)
				.map(IndexedCity::candidate)
				.toList();
	}

	public LocationCandidate findById(String id) {
		// 前端选中候选城市后会把 id 传回来，这里用 id 精确定位城市。
		if (id == null || id.isBlank()) {
			return null;
		}
		return cities.stream()
				.filter(city -> city.id().equals(id))
				.findFirst()
				.map(IndexedCity::candidate)
				.orElse(null);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	record IndexedCity(
			String id,
			String name,
			String displayName,
			String country,
			String countryCode,
			String admin1,
			String timezone,
			double latitude,
			double longitude,
			List<String> aliases
	) {
		boolean matches(String query) {
			return normalize(name).contains(query)
					|| normalize(displayName).contains(query)
					|| aliases.stream().anyMatch(alias -> normalize(alias).contains(query) || query.contains(normalize(alias)));
		}

		int rank(String query) {
			if (normalize(name).equals(query) || aliases.stream().anyMatch(alias -> normalize(alias).equals(query))) {
				return 0;
			}
			if (normalize(name).startsWith(query)) {
				return 1;
			}
			return 2;
		}

		LocationCandidate candidate() {
			return new LocationCandidate(id, name, displayName, country, countryCode, admin1, timezone, latitude, longitude);
		}
	}
}
