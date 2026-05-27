package com.example.weatherapp.recommendations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class LocalRecommendationGenerator {

	private static final Logger log = LoggerFactory.getLogger(LocalRecommendationGenerator.class);
	private static final int CATEGORY_SIZE = 5;

	private final ObjectMapper objectMapper;
	private final WebClient aiClient;
	private final String aiApiKey;
	private final String aiModel;

	public LocalRecommendationGenerator(
			ObjectMapper objectMapper,
			WebClient.Builder webClientBuilder,
			@Value("${app.ai.base-url:}") String aiBaseUrl,
			@Value("${app.ai.api-key:}") String aiApiKey,
			@Value("${app.ai.model:gpt-5.5}") String aiModel
	) {
		this.objectMapper = objectMapper;
		this.aiClient = webClientBuilder.baseUrl(normalizeAiBaseUrl(aiBaseUrl)).build();
		this.aiApiKey = aiApiKey == null ? "" : aiApiKey;
		this.aiModel = aiModel == null || aiModel.isBlank() ? "gpt-5.5" : aiModel;
	}

	public Mono<List<RecommendationSeed>> generate(String displayName, int offset) {
		if (aiApiKey.isBlank()) {
			return Mono.just(fallback(displayName, offset));
		}
		return aiClient.post()
				.uri("/chat/completions")
				.header("Authorization", "Bearer " + aiApiKey)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of(
						"model", aiModel,
						"messages", List.of(
								Map.of("role", "system", "content", instructions()),
								Map.of("role", "user", "content", prompt(displayName, offset))
						),
						"response_format", Map.of("type", "json_object"),
						"max_tokens", 3500,
						"store", false
				))
				.retrieve()
				.bodyToMono(String.class)
				.timeout(Duration.ofSeconds(20))
				.map(this::parseResponse)
				.filter(seeds -> categoryCount(seeds, RecommendationCategory.food) >= CATEGORY_SIZE
						&& categoryCount(seeds, RecommendationCategory.place) >= CATEGORY_SIZE)
				.onErrorResume(error -> {
					log.warn("AI recommendation request failed, using fallback seeds: {}", error.getMessage());
					return Mono.just(fallback(displayName, offset));
				})
				.defaultIfEmpty(fallback(displayName, offset));
	}

	public boolean aiConfigured() {
		return !aiApiKey.isBlank();
	}

	private String instructions() {
		return """
				你为天气应用生成真实本地推荐。必须只返回 JSON，不要 Markdown。
				美食必须有强地域性：优先选择离开这个城市或地区后很难吃到正宗版本的地方名吃、老字号代表菜、非遗/地方小吃；不要给“冷面、锅贴、小笼包、白斩鸡”这类过于泛化、多个城市都常见的项目，除非它在该地区有明确地方流派并在描述里写清楚。
				游玩地点必须是真实存在的具体地点，不要使用“城市地标、历史街区、滨水步道”等模板词。
				名称不要包含城市名、省名、国家名或行政区前缀。
				描述要具体，说明地域关联、为什么值得去或适合什么体验。
				sourceUrl 必须是可访问的 http/https 信息来源，优先官方、百科、文旅或地图/点评类页面。
				imageUrl 必须是推荐对象本身的真实图片直链，优先官方图片、Wikimedia Commons 直链或可信百科图片直链；必须能直接作为 img src 使用。不要使用 Unsplash、Pexels、Pixabay、占位图、图库泛图、搜索结果页、网页 URL 或空字符串。如果找不到真实图片直链，就换一个有真实图片直链的推荐项。
				""";
	}

	private String prompt(String displayName, int offset) {
		var region = displayName == null || displayName.isBlank() ? "当前地区" : displayName.trim();
		return """
				地区：%s
				批次偏移：%d

				请返回这个地区的 5 个真实美食推荐和 5 个真实游玩地点推荐。不同偏移应尽量给出不同项目。
				JSON 结构：
				{
				  "foods": [
				    {"name": "...", "description": "...", "imageUrl": "https://...jpg", "imageAlt": "...", "sourceTitle": "...", "sourceUrl": "https://..."}
				  ],
				  "places": [
				    {"name": "...", "description": "...", "imageUrl": "https://...jpg", "imageAlt": "...", "sourceTitle": "...", "sourceUrl": "https://..."}
				  ]
				}
				""".formatted(region, offset);
	}

	private List<RecommendationSeed> parseResponse(String payload) {
		var content = extractText(payload);
		if (content == null || content.isBlank()) {
			return List.of();
		}
		var json = readJson(content);
		if (json == null) {
			return List.of();
		}
		var result = new ArrayList<RecommendationSeed>();
		result.addAll(parseItems(json.path("foods"), RecommendationCategory.food));
		result.addAll(parseItems(json.path("places"), RecommendationCategory.place));
		return result;
	}

	private String extractText(String payload) {
		var response = readJson(payload);
		if (response == null) {
			return "";
		}
		var outputText = response.path("output_text").asText("");
		if (!outputText.isBlank()) {
			return outputText;
		}
		var content = response.path("output").path(0).path("content").path(0).path("text").asText("");
		if (!content.isBlank()) {
			return content;
		}
		return response.path("choices").path(0).path("message").path("content").asText("");
	}

	private JsonNode readJson(String payload) {
		try {
			return objectMapper.readTree(payload);
		} catch (JsonProcessingException error) {
			log.warn("Failed to parse recommendation JSON: {}", error.getMessage());
			return null;
		}
	}

	private List<RecommendationSeed> parseItems(JsonNode items, RecommendationCategory category) {
		if (!items.isArray()) {
			return List.of();
		}
		var result = new ArrayList<RecommendationSeed>();
		for (var item : items) {
			var name = item.path("name").asText("").trim();
			var description = item.path("description").asText("").trim();
			var sourceUrl = item.path("sourceUrl").asText("").trim();
			var imageUrl = item.path("imageUrl").asText("").trim();
			if (name.isBlank() || description.isBlank() || !validUrl(sourceUrl) || !validImageUrl(imageUrl)) {
				continue;
			}
			var sourceTitle = item.path("sourceTitle").asText("").trim();
			var imageAlt = item.path("imageAlt").asText("").trim();
			result.add(new RecommendationSeed(
					category,
					name,
					description,
					imageUrl,
					imageAlt.isBlank() ? name + "图片" : imageAlt,
					sourceTitle.isBlank() ? "信息来源" : sourceTitle,
					sourceUrl,
					false
			));
		}
		return result;
	}

	private List<RecommendationSeed> fallback(String displayName, int offset) {
		var catalog = fallbackCatalog(displayName);
		var result = new ArrayList<RecommendationSeed>();
		for (int index = 0; index < catalog.foods().size(); index++) {
			var name = catalog.foods().get(Math.floorMod(index + offset, catalog.foods().size()));
			result.add(new RecommendationSeed(
					RecommendationCategory.food,
					name,
					name + catalog.foodDescription(),
					fallbackImageUrl(name),
					name + "图片",
					name + "公开资料",
					"https://baike.baidu.com/item/" + name,
					true
			));
		}
		for (int index = 0; index < catalog.places().size(); index++) {
			var name = catalog.places().get(Math.floorMod(index + offset, catalog.places().size()));
			result.add(new RecommendationSeed(
					RecommendationCategory.place,
					name,
					name + catalog.placeDescription(),
					fallbackImageUrl(name),
					name + "图片",
					name + "公开资料",
					"https://baike.baidu.com/item/" + name,
					true
			));
		}
		return result;
	}

	private static FallbackCatalog fallbackCatalog(String displayName) {
		var region = displayName == null ? "" : displayName;
		if (region.contains("北京")) {
			return new FallbackCatalog(
					List.of("豆汁", "卤煮火烧", "炒肝", "爆肚", "驴打滚", "豌豆黄", "艾窝窝", "门钉肉饼", "炸酱面", "焦圈", "糖火烧", "羊蝎子", "芥末墩", "褡裢火烧", "北京烤鸭"),
					List.of("故宫博物院", "天坛公园", "颐和园", "八达岭长城", "雍和宫", "景山公园", "北海公园", "国家博物馆", "什刹海", "南锣鼓巷", "圆明园", "恭王府", "香山公园", "孔庙和国子监", "首都博物馆"),
					"带有北京街巷、宫廷点心或老字号饮食传统，地域辨识度强。",
					"是当地代表性文化或历史地点，适合结合天气安排半日或一日路线。"
			);
		}
		if (region.contains("广州")) {
			return new FallbackCatalog(
					List.of("艇仔粥", "云吞面", "肠粉", "虾饺", "干蒸烧卖", "叉烧包", "双皮奶", "姜撞奶", "泮塘马蹄糕", "萝卜牛杂", "及第粥", "濑粉", "钵仔糕", "白切鸡", "煲仔饭"),
					List.of("陈家祠", "越秀公园", "沙面", "广州塔", "广东省博物馆", "永庆坊", "北京路", "荔枝湾涌", "白云山", "中山纪念堂", "南越王博物院", "石室圣心大教堂", "黄埔军校旧址", "海心沙", "番禺宝墨园"),
					"和广府早茶、骑楼街市或珠三角饮食传统关联紧密。",
					"能体现广府历史、岭南建筑或城市公共生活，适合按天气安排游览。"
			);
		}
		if (region.contains("深圳")) {
			return new FallbackCatalog(
					List.of("光明乳鸽", "沙井蚝", "公明烧鹅", "南澳海胆", "客家盆菜", "龙岗三黄鸡", "西乡基围虾", "松岗腊鸭", "大鹏将军鸭", "坪山金龟橘", "盐田海鲜", "观澜狗肉", "葵涌窑鸡", "客家酿豆腐", "海胆炒饭"),
					List.of("莲花山公园", "深圳博物馆", "大鹏所城", "甘坑古镇", "华侨城创意文化园", "南头古城", "深圳湾公园", "梧桐山", "东部华侨城", "海上世界", "中英街", "仙湖植物园", "大梅沙海滨公园", "人才公园", "光明小镇"),
					"更贴近深圳本地村镇、海岸和客家饮食脉络，不是泛化快餐推荐。",
					"覆盖本地历史、海岸、公园和城市更新空间，适合按天气选择室内外路线。"
			);
		}
		if (region.contains("杭州")) {
			return new FallbackCatalog(
					List.of("西湖醋鱼", "龙井虾仁", "东坡肉", "片儿川", "定胜糕", "葱包桧", "宋嫂鱼羹", "虾爆鳝面", "知味小笼", "猫耳朵", "油冬儿", "酱鸭", "藕粉", "吴山烤鸡", "桂花糖年糕"),
					List.of("西湖", "灵隐寺", "西溪湿地", "中国茶叶博物馆", "良渚古城遗址公园", "河坊街", "京杭大运河杭州段", "杭州博物馆", "雷峰塔", "九溪烟树", "太子湾公园", "胡雪岩故居", "南宋御街", "径山寺", "湘湖"),
					"和西湖、钱塘、茶文化或杭帮菜传统关联紧密。",
					"体现杭州山水、宋韵和茶文化，适合结合天气安排慢行路线。"
			);
		}
		if (region.contains("南京")) {
			return new FallbackCatalog(
					List.of("鸭血粉丝汤", "盐水鸭", "小笼包", "牛肉锅贴", "赤豆元宵", "梅花糕", "皮肚面", "桂花糖芋苗", "鸭油烧饼", "鸡汁汤包", "南京烤鸭", "六合猪头肉", "活珠子", "什锦菜", "糖粥藕"),
					List.of("中山陵", "明孝陵", "夫子庙秦淮风光带", "南京博物院", "总统府", "玄武湖", "鸡鸣寺", "老门东", "阅江楼", "南京城墙", "侵华日军南京大屠杀遇难同胞纪念馆", "栖霞山", "牛首山", "瞻园", "雨花台"),
					"和金陵小吃、鸭馔或秦淮街巷饮食传统关联紧密。",
					"体现六朝古都、明城墙和秦淮文化，适合按天气安排室内外组合。"
			);
		}
		if (region.contains("成都")) {
			return new FallbackCatalog(
					List.of("担担面", "龙抄手", "钟水饺", "夫妻肺片", "甜水面", "兔头", "钵钵鸡", "赖汤圆", "三大炮", "蛋烘糕", "冒菜", "锅盔", "麻婆豆腐", "肥肠粉", "川北凉粉"),
					List.of("武侯祠", "杜甫草堂", "宽窄巷子", "锦里", "成都博物馆", "青羊宫", "人民公园", "东郊记忆", "金沙遗址博物馆", "文殊院", "望江楼公园", "青城山", "都江堰", "大熊猫繁育研究基地", "太古里"),
					"具有川西坝子和成都街头小吃辨识度，辣味、红油和茶馆文化特征明显。",
					"覆盖蜀汉文化、诗意园林、茶馆生活和周边山水，适合按天气规划。"
			);
		}
		if (region.contains("New York") || region.contains("纽约")) {
			return new FallbackCatalog(
					List.of("New York Bagel", "Pastrami on Rye", "New York Cheesecake", "Dollar Slice", "Black and White Cookie", "Knish", "Egg Cream", "Chopped Cheese", "Lobster Roll", "Halal Cart Chicken and Rice", "Matzo Ball Soup", "Cronut", "Bialy", "Hot Dog", "Manhattan Clam Chowder"),
					List.of("Central Park", "Metropolitan Museum of Art", "Statue of Liberty", "Brooklyn Bridge", "High Line", "Museum of Modern Art", "Grand Central Terminal", "Times Square", "American Museum of Natural History", "Bryant Park", "Chelsea Market", "DUMBO", "New York Public Library", "Rockefeller Center", "Tenement Museum"),
					" is closely tied to New York deli, bakery, street food, or borough food culture.",
					" is a representative local landmark or museum, suitable for planning around weather and transit."
			);
		}
		if (region.contains("London") || region.contains("伦敦")) {
			return new FallbackCatalog(
					List.of("Pie and Mash", "Jellied Eels", "Salt Beef Beigel", "Sunday Roast", "Fish and Chips", "Full English Breakfast", "Chelsea Bun", "Scotch Egg", "Eton Mess", "Eccles Cake", "Borough Market Oysters", "Ploughman's Lunch", "Afternoon Tea", "Sticky Toffee Pudding", "Saveloy"),
					List.of("British Museum", "Tower of London", "Westminster Abbey", "National Gallery", "Tate Modern", "Hyde Park", "St Paul's Cathedral", "Borough Market", "Victoria and Albert Museum", "Natural History Museum", "Greenwich Park", "Camden Market", "Covent Garden", "Kew Gardens", "Shakespeare's Globe"),
					" has a clear London market, pub, bakery, or East End food connection.",
					" is a representative London museum, market, park, or historic site suitable for weather-aware planning."
			);
		}
		if (region.contains("上海")) {
			return new FallbackCatalog(
					List.of("三虾面", "枫泾丁蹄", "下沙烧卖", "崇明糕", "高桥松饼", "南翔小笼", "条头糕薄荷糕", "梨膏糖", "鲜肉月饼", "草头圈子", "油墩子", "蟹壳黄", "排骨年糕", "罗宋汤", "八宝鸭"),
					List.of("外滩", "豫园", "上海博物馆", "武康路", "上海自然博物馆", "田子坊", "朱家角古镇", "中华艺术宫", "上海天文馆", "思南公馆", "龙华寺", "静安寺", "上海当代艺术博物馆", "共青森林公园", "徐家汇书院"),
					"和上海本帮、郊区风物或老字号饮食传统关联紧密，适合作为更有地域辨识度的风味体验。",
					"是上海较有代表性的游玩地点，适合结合天气和交通安排半日或一日行程。"
			);
		}
		return new FallbackCatalog(
				List.of("地方传统小吃", "老字号招牌菜", "本地节令点心", "街市风味小吃", "地方面点", "本地家常菜", "区域特色汤品", "传统糕团", "地方早餐", "本地夜市小食", "非遗风味点心", "地方宴席菜", "本地卤味", "传统甜品", "区域特色饮品"),
				List.of("当地博物馆", "历史文化街区", "城市公园", "传统市场", "地方美术馆", "老城步行街", "文化遗址", "公共图书馆", "民俗展馆", "滨水公共空间", "地方剧场", "历史建筑群", "自然保护地", "城市广场", "社区文化中心"),
				"需要结合 AI 或当地资料进一步细化；当前仅作为无 AI 配置时的通用占位。",
				"需要结合 AI 或当地资料进一步细化；当前仅作为无 AI 配置时的通用占位。"
		);
	}

	private record FallbackCatalog(
			List<String> foods,
			List<String> places,
			String foodDescription,
			String placeDescription
	) {
	}

	private static long categoryCount(List<RecommendationSeed> seeds, RecommendationCategory category) {
		return seeds.stream().filter(seed -> seed.category() == category).count();
	}

	private static boolean validUrl(String value) {
		try {
			var uri = URI.create(value);
			return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
		} catch (RuntimeException error) {
			return false;
		}
	}

	private static boolean validImageUrl(String value) {
		if (!validUrl(value)) {
			return false;
		}
		var normalized = value.toLowerCase();
		return !normalized.contains("unsplash.com")
				&& !normalized.contains("pexels.com")
				&& !normalized.contains("pixabay.com")
				&& !normalized.contains("placeholder")
				&& !normalized.contains("example.com")
				&& (normalized.contains(".jpg")
						|| normalized.contains(".jpeg")
						|| normalized.contains(".png")
						|| normalized.contains(".webp")
						|| normalized.contains("special:redirect/file"));
	}

	private static String fallbackImageUrl(String name) {
		return "https://commons.wikimedia.org/wiki/Special:Redirect/file/" + name + ".jpg";
	}

	private static String normalizeAiBaseUrl(String aiBaseUrl) {
		if (aiBaseUrl == null || aiBaseUrl.isBlank()) {
			return "http://localhost";
		}
		var trimmed = aiBaseUrl.trim();
		if (trimmed.endsWith("/chat/completions")) {
			return trimmed.substring(0, trimmed.length() - "/chat/completions".length());
		}
		if (trimmed.endsWith("/v1")) {
			return trimmed;
		}
		if (trimmed.endsWith("/v1/")) {
			return trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed.endsWith("/") ? trimmed + "v1" : trimmed + "/v1";
	}
}
