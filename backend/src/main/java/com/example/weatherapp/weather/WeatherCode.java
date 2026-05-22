package com.example.weatherapp.weather;

final class WeatherCode {

	private WeatherCode() {
	}

	static String describe(Integer code) {
		if (code == null) {
			return "天气数据暂不可用";
		}
		return switch (code) {
			case 0 -> "晴";
			case 1, 2 -> "少云";
			case 3 -> "阴";
			case 45, 48 -> "雾";
			case 51, 53, 55, 56, 57 -> "毛毛雨";
			case 61, 63, 65, 66, 67, 80, 81, 82 -> "雨";
			case 71, 73, 75, 77, 85, 86 -> "雪";
			case 95, 96, 99 -> "雷暴";
			default -> "多变";
		};
	}
}
