<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

// 下面这些 type 用来描述后端接口返回的数据结构，方便 TypeScript 检查字段是否写错。
type LocationCandidate = {
  id: string
  name: string
  displayName: string
  country: string | null
  countryCode: string | null
  admin1: string | null
  timezone: string | null
  latitude: number
  longitude: number
}

type WeatherCurrent = {
  time: string | null
  temperatureCelsius: number | null
  humidityPercent: number | null
  windSpeedKmh: number | null
  windDirectionDegrees: number | null
  precipitationMm: number | null
  weatherCode: number | null
  condition: string
}

type WeatherForecastDay = {
  date: string
  highCelsius: number | null
  lowCelsius: number | null
  precipitationProbabilityPercent: number | null
  weatherCode: number | null
  condition: string
}

type WeatherResult = {
  location: LocationCandidate
  current: WeatherCurrent
  forecast: WeatherForecastDay[]
  sourceName: string
  timezone: string | null
  retrievedAt: string
}

type WeatherSearchResponse = {
  query: string
  status: 'resolved' | 'ambiguous' | 'unsupported'
  message: string
  candidates: LocationCandidate[]
  weather: WeatherResult | null
}

type AnonymousSessionResponse = {
  userId: string
  sessionId: string
  identityType: string
  createdAt: string
}

type AuthResponse = {
  userId: string
  email: string
  displayName: string
  identityType: 'registered'
  token: string
  expiresAt: string
}

type SessionResponse = {
  userId: string
  email: string
  displayName: string
  identityType: 'registered'
}

type FavoriteCity = {
  id: string
  providerLocationId: string
  displayName: string
  countryCode: string | null
  latitude: number
  longitude: number
  latestTemperatureCelsius: number | null
  latestCondition: string | null
  weatherAvailable: boolean
  weatherStatus: 'available' | 'stale' | 'unavailable'
  weatherUpdatedAt: string | null
  createdAt: string
}

type ChatMessage = {
  id: number
  role: 'user' | 'assistant'
  content: string
}

type AssistantResponse = {
  answer: string
  usedWeatherContext: boolean
  persisted: boolean
  createdAt: string
}

type NotificationSubscription = {
  id: string
  favoriteCityId: string
  favoriteDisplayName: string
  channel: 'web_push' | 'in_app'
  permissionStatus: NotificationPermission | 'unsupported'
  dailySummaryEnabled: boolean
  severeWeatherEnabled: boolean
  cooldownMinutes: number
  active: boolean
  createdAt: string
  updatedAt: string
}

type NotificationEvent = {
  id: string
  subscriptionId: string
  favoriteCityId: string
  favoriteDisplayName: string
  eventType: 'severe_weather' | 'daily_summary' | 'weather_change'
  title: string
  body: string
  severity: string | null
  deliveryStatus: 'pending' | 'sent' | 'suppressed' | 'failed'
  createdAt: string
}

type NotificationEvaluationResponse = {
  generated: number
  suppressed: number
  events: NotificationEvent[]
  evaluatedAt: string
}

type WebPushConfigResponse = {
  publicKey: string
  configured: boolean
  checkedAt: string
}

type LocalRecommendationItem = {
  id: string
  category: 'food' | 'place'
  rank: number
  name: string
  description: string
  imageUrl: string
  imageAlt: string
  sourceTitle: string
  sourceUrl: string
  batchId: string
}

type LocalRecommendationResponse = {
  batchId: string
  providerLocationId: string
  displayName: string
  foods: LocalRecommendationItem[]
  places: LocalRecommendationItem[]
  hasMore: boolean
  message: string
  generatedAt: string
}

// 后端接口地址。开发环境里前端 5173 端口会请求后端 8080 端口。
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
const webPushPublicKey = ref(import.meta.env.VITE_WEB_PUSH_PUBLIC_KEY ?? '')

// 页面输入、加载状态和提示信息。ref 的值改变后，页面会自动重新渲染。
const cityQuery = ref('')
const selectedCity = ref('上海，中国')
const assistantQuestion = ref('')
const notificationsEnabled = ref(false)
const loading = ref(false)
const favoritesLoading = ref(false)
const assistantLoading = ref(false)
const errorMessage = ref('')
const favoriteMessage = ref('')
const assistantMessage = ref('')
const assistantOpen = ref(false)
const assistantButtonLeft = ref(16)
const assistantButtonTop = ref<number | null>(null)
const assistantButtonMoved = ref(false)
const notificationMessage = ref('')
const notificationsEvaluating = ref(false)
const notificationTesting = ref(false)
const notificationsStreaming = ref(false)
const recommendationsLoading = ref(false)
const recommendationsRefreshing = ref(false)
const webPushConfigured = ref(false)
const userId = ref(localStorage.getItem('weather-app-user-id') ?? '')
const authToken = ref(localStorage.getItem('weather-app-auth-token') ?? '')
const authMode = ref<'login' | 'register'>('login')
const authEmail = ref('')
const authPassword = ref('')
const authDisplayName = ref('')
const authLoading = ref(false)
const authMessage = ref('')
const authMenuOpen = ref(false)
const currentUser = ref<SessionResponse | null>(null)
const candidates = ref<LocationCandidate[]>([])
const weather = ref<WeatherResult | null>(null)
const favorites = ref<FavoriteCity[]>([])
const notificationSubscriptions = ref<NotificationSubscription[]>([])
const notificationEvents = ref<NotificationEvent[]>([])
const districtCandidates = ref<LocationCandidate[]>([])
const localRecommendations = ref<LocalRecommendationResponse | null>(null)
const recommendationMessage = ref('')
let recommendationRequestId = 0
let notificationStream: EventSource | undefined
let notificationEventStream: EventSource | undefined
let suggestionTimer: number | undefined
let suggestionRequestId = 0
let assistantDragStart:
  | {
      pointerId: number
      x: number
      y: number
      left: number
      top: number
      moved: boolean
    }
  | undefined

// 天气助手的聊天记录，页面会根据这个数组循环渲染对话气泡。
const chatMessages = ref<ChatMessage[]>([
    {
      id: 1,
      role: 'assistant',
      content:
      '请先搜索一个城市，然后可以询问出行、穿衣、降雨、风力、景点安排或当前预报相关问题。',
    },
  ])

const canSearch = computed(() => cityQuery.value.trim().length > 0)
const forecast = computed(() => weather.value?.forecast ?? [])
const current = computed(() => weather.value?.current ?? null)
const activeCandidateIndex = ref(-1)
const recommendationFoods = computed(() => localRecommendations.value?.foods ?? [])
const recommendationPlaces = computed(() => localRecommendations.value?.places ?? [])
const displayedRecommendationIds = computed(() => [
  ...recommendationFoods.value.map((item) => item.id),
  ...recommendationPlaces.value.map((item) => item.id),
])
const canRefreshRecommendations = computed(
  () => Boolean(weather.value && localRecommendations.value) && !recommendationsLoading.value && !recommendationsRefreshing.value,
)

// 根据当前天气城市，判断它是否已经存在于收藏列表中。
const currentFavorite = computed(() => {
  const locationId = weather.value?.location.id
  if (!locationId) {
    return null
  }
  return favorites.value.find((favorite) => favorite.providerLocationId === locationId) ?? null
})
const currentNotification = computed(() => {
  const favoriteId = currentFavorite.value?.id
  if (!favoriteId) {
    return null
  }
  return (
    notificationSubscriptions.value.find(
      (subscription) => subscription.favoriteCityId === favoriteId && subscription.active,
    ) ?? null
  )
})
const notificationChannelText = computed(() => {
  const subscription = currentNotification.value
  if (!subscription) {
    return '未开启'
  }
  return subscription.channel === 'web_push' ? '后台 Web Push' : '应用内通知'
})
const browserNotificationText = computed(() => {
  if (!('Notification' in window)) {
    return '浏览器不支持'
  }
  return Notification.permission === 'granted' ? '已授权' : Notification.permission === 'denied' ? '已拒绝' : '未授权'
})
const assistantButtonStyle = computed(() => ({
  left: `${assistantButtonLeft.value}px`,
  top: assistantButtonTop.value === null ? '50%' : `${assistantButtonTop.value}px`,
  transform: assistantButtonTop.value === null ? 'translateY(-50%)' : 'none',
}))

// 页面第一次加载时执行：恢复登录状态、创建匿名身份、读取收藏/通知，并默认查询上海天气。
onMounted(async () => {
  try {
    await restoreAuthSession()
    await ensureAnonymousSession()
    await loadFavorites()
    await loadNotifications()
    await loadNotificationEvents()
    await loadWebPushConfig()
    connectNotificationStream()
    connectNotificationEventStream()
    if (!weather.value) {
      await loadInitialWeather()
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '应用初始化失败。'
  }
})

onBeforeUnmount(() => {
  notificationStream?.close()
  notificationEventStream?.close()
})

// 监听搜索框输入，延迟 250ms 再请求候选城市，避免用户每输入一个字就立刻请求后端。
watch(cityQuery, (value) => {
  const query = value.trim()
  window.clearTimeout(suggestionTimer)

  if (!query || query === weather.value?.location.name || query === weather.value?.location.displayName) {
    activeCandidateIndex.value = -1
    return
  }

  suggestionTimer = window.setTimeout(() => {
    void suggestCities(query)
  }, 250)
})

function authHeaders() {
  return {
    'Content-Type': 'application/json',
    'X-User-Id': userId.value,
  }
}

function tokenHeaders() {
  return {
    'Content-Type': 'application/json',
    'X-Auth-Token': authToken.value,
  }
}

// 如果用户没有登录，也会创建一个匿名用户 ID，保证收藏、通知、AI 记录都能归属到某个用户。
async function ensureAnonymousSession() {
  if (userId.value) {
    return
  }

  const response = await fetch(`${apiBaseUrl}/api/sessions/anonymous`, { method: 'POST' })
  if (!response.ok) {
    throw new Error(`创建匿名会话失败，状态码 ${response.status}`)
  }

  const session = (await response.json()) as AnonymousSessionResponse
  userId.value = session.userId
  localStorage.setItem('weather-app-user-id', session.userId)
  localStorage.setItem('weather-app-session-id', session.sessionId)
}

// 页面刷新后尝试用 localStorage 里的 token 恢复登录；失败就清掉过期登录状态。
async function restoreAuthSession() {
  if (!authToken.value) {
    return
  }

  try {
    const response = await fetch(`${apiBaseUrl}/api/auth/me`, {
      headers: tokenHeaders(),
    })
    if (!response.ok) {
      throw new Error('登录已过期。')
    }
    const session = (await response.json()) as SessionResponse
    applySession(session, authToken.value)
  } catch {
    clearAuthSession()
  }
}

// 登录和注册共用这个函数，通过 authMode 决定请求 /login 还是 /register。
async function submitAuth() {
  if (!authEmail.value.trim() || !authPassword.value) {
    authMessage.value = '请输入邮箱和密码。'
    return
  }

  authLoading.value = true
  authMessage.value = ''
  try {
    const response = await fetch(`${apiBaseUrl}/api/auth/${authMode.value}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: authEmail.value,
        password: authPassword.value,
        displayName: authDisplayName.value,
      }),
    })
    if (!response.ok) {
      throw new Error(authMode.value === 'login' ? '登录失败，请检查邮箱和密码。' : '注册失败，邮箱可能已被使用。')
    }
    const session = (await response.json()) as AuthResponse
    applySession(session, session.token)
    authPassword.value = ''
    authMessage.value = authMode.value === 'login' ? '已登录。' : '注册成功，已登录。'
    authMenuOpen.value = false
    await reloadUserData()
  } catch (error) {
    authMessage.value = error instanceof Error ? error.message : '账户操作失败。'
  } finally {
    authLoading.value = false
  }
}

async function logout() {
  if (authToken.value) {
    await fetch(`${apiBaseUrl}/api/auth/logout`, {
      method: 'POST',
      headers: tokenHeaders(),
    }).catch(() => undefined)
  }

  clearAuthSession()
  await ensureAnonymousSession()
  await reloadUserData()
  authMessage.value = '已退出登录，当前使用匿名会话。'
}

// 登录成功后，把用户信息和 token 保存到内存及 localStorage，刷新页面也能继续使用。
function applySession(session: SessionResponse | AuthResponse, token: string) {
  currentUser.value = {
    userId: session.userId,
    email: session.email,
    displayName: session.displayName,
    identityType: session.identityType,
  }
  authToken.value = token
  userId.value = session.userId
  localStorage.setItem('weather-app-auth-token', token)
  localStorage.setItem('weather-app-user-id', session.userId)
}

function clearAuthSession() {
  currentUser.value = null
  authToken.value = ''
  userId.value = ''
  localStorage.removeItem('weather-app-auth-token')
  localStorage.removeItem('weather-app-user-id')
}

// 用户身份变化后，需要重新读取和这个用户相关的收藏、通知和实时推送连接。
async function reloadUserData() {
  notificationStream?.close()
  notificationStream = undefined
  notificationEventStream?.close()
  notificationEventStream = undefined
  favorites.value = []
  notificationSubscriptions.value = []
  notificationEvents.value = []
  await loadFavorites()
  await loadNotifications()
  await loadNotificationEvents()
  connectNotificationStream()
  connectNotificationEventStream()
}

// 读取当前用户收藏的城市，后端会附带最近一次缓存天气，方便列表显示温度。
async function loadFavorites() {
  if (!userId.value) {
    return
  }

  favoritesLoading.value = true
  try {
    const response = await fetch(`${apiBaseUrl}/api/favorites`, {
      headers: authHeaders(),
    })
    if (!response.ok) {
      throw new Error(`读取收藏城市失败，状态码 ${response.status}`)
    }
    favorites.value = (await response.json()) as FavoriteCity[]
  } catch (error) {
    favoriteMessage.value = error instanceof Error ? error.message : '读取收藏城市失败。'
  } finally {
    favoritesLoading.value = false
  }
}

async function loadNotifications() {
  if (!userId.value) {
    return
  }

  const response = await fetch(`${apiBaseUrl}/api/notifications/subscriptions`, {
    headers: authHeaders(),
  })
  if (!response.ok) {
    notificationMessage.value = `读取通知状态失败，状态码 ${response.status}`
    return
  }
  notificationSubscriptions.value = (await response.json()) as NotificationSubscription[]
  notificationsEnabled.value = Boolean(currentNotification.value)
}

async function loadNotificationEvents() {
  if (!userId.value) {
    return
  }

  const response = await fetch(`${apiBaseUrl}/api/notifications/subscriptions/events`, {
    headers: authHeaders(),
  })
  if (!response.ok) {
    return
  }
  notificationEvents.value = (await response.json()) as NotificationEvent[]
}

async function loadWebPushConfig() {
  const response = await fetch(`${apiBaseUrl}/api/status/web-push`)
  if (!response.ok) {
    return
  }
  const config = (await response.json()) as WebPushConfigResponse
  webPushConfigured.value = config.configured
  if (config.configured && config.publicKey) {
    webPushPublicKey.value = config.publicKey
  }
}

// 建立 SSE 长连接，后端有通知订阅变化时会推送到前端。
function connectNotificationStream() {
  if (!userId.value || notificationStream) {
    return
  }

  const params = new URLSearchParams({ userId: userId.value })
  notificationStream = new EventSource(
    `${apiBaseUrl}/api/notifications/subscriptions/stream?${params.toString()}`,
  )
  notificationStream.addEventListener('notification-subscriptions', (event) => {
    notificationSubscriptions.value = JSON.parse(event.data) as NotificationSubscription[]
    notificationsEnabled.value = Boolean(currentNotification.value)
    notificationsStreaming.value = true
  })
  notificationStream.onerror = () => {
    notificationsStreaming.value = false
  }
}

// 建立通知事件流，用来实时显示“收到天气提醒”这类记录。
function connectNotificationEventStream() {
  if (!userId.value || notificationEventStream) {
    return
  }

  const params = new URLSearchParams({ userId: userId.value })
  notificationEventStream = new EventSource(
    `${apiBaseUrl}/api/notifications/events/stream?${params.toString()}`,
  )
  notificationEventStream.addEventListener('notification-events', (event) => {
    const events = JSON.parse(event.data) as NotificationEvent[]
    if (!events.length) {
      notificationsStreaming.value = true
      return
    }
    notificationEvents.value = [...events, ...notificationEvents.value].slice(0, 50)
    notificationMessage.value = `收到 ${events.length} 条天气风险提醒。`
    notificationsStreaming.value = true
  })
  notificationEventStream.onerror = () => {
    notificationsStreaming.value = false
  }
}

// 把当前查询到的城市保存到收藏表。
async function addCurrentFavorite() {
  if (!weather.value) {
    return
  }

  await ensureAnonymousSession()
  favoriteMessage.value = ''
  const location = weather.value.location
  const response = await fetch(`${apiBaseUrl}/api/favorites`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({
      providerLocationId: location.id,
      displayName: location.displayName,
      countryCode: location.countryCode,
      latitude: location.latitude,
      longitude: location.longitude,
    }),
  })

  if (!response.ok) {
    favoriteMessage.value = `保存收藏失败，状态码 ${response.status}`
    return
  }

  await loadFavorites()
  await loadNotifications()
  await loadNotificationEvents()
}

async function removeFavorite(favorite: FavoriteCity) {
  if (!userId.value) {
    return
  }

  favoriteMessage.value = ''
  const response = await fetch(`${apiBaseUrl}/api/favorites/${favorite.id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  })

  if (!response.ok) {
    favoriteMessage.value = `移除收藏失败，状态码 ${response.status}`
    return
  }

  favorites.value = favorites.value.filter((item) => item.id !== favorite.id)
  await loadNotifications()
  await loadNotificationEvents()
}

// 点击收藏城市时，直接用收藏里的经纬度和城市 ID 重新查询天气。
function selectFavorite(favorite: FavoriteCity) {
  selectedCity.value = favorite.displayName
  cityQuery.value = favorite.displayName
  void searchWeather({
    id: favorite.providerLocationId,
    name: favorite.displayName.split(',')[0],
    displayName: favorite.displayName,
    country: null,
    countryCode: favorite.countryCode,
    admin1: null,
    timezone: null,
    latitude: favorite.latitude,
    longitude: favorite.longitude,
  })
}

function submitSearch() {
  if (candidates.value.length) {
    const candidate = candidates.value[Math.max(activeCandidateIndex.value, 0)]
    void selectCandidate(candidate)
    return
  }

  void searchWeather()
}

async function selectCandidate(candidate: LocationCandidate) {
  cityQuery.value = candidate.name
  activeCandidateIndex.value = -1
  await searchWeather(candidate)
}

function moveCandidate(delta: number) {
  if (!candidates.value.length) {
    return
  }

  activeCandidateIndex.value =
    (activeCandidateIndex.value + delta + candidates.value.length) % candidates.value.length
}

async function loadInitialWeather() {
  try {
    await searchWeatherByCurrentPosition()
  } catch {
    await searchWeather({
      id: 'shanghai-cn',
      name: '上海',
      displayName: '上海, 上海市, 中国',
      country: '中国',
      countryCode: 'CN',
      admin1: '上海市',
      timezone: 'Asia/Shanghai',
      latitude: 31.2304,
      longitude: 121.4737,
    })
  }
}

async function searchWeatherByCurrentPosition() {
  const position = await getCurrentPosition()
  loading.value = true
  errorMessage.value = ''
  candidates.value = []
  activeCandidateIndex.value = -1

  try {
    const params = new URLSearchParams({
      latitude: String(position.coords.latitude),
      longitude: String(position.coords.longitude),
    })
    const response = await fetch(`${apiBaseUrl}/api/weather/nearby?${params.toString()}`)
    if (!response.ok) {
      throw new Error(`定位天气查询失败，状态码 ${response.status}`)
    }

    const payload = (await response.json()) as WeatherSearchResponse
    if (applyWeatherPayload(payload)) {
      await loadFavorites()
      await loadNotifications()
    }
  } catch (error) {
    weather.value = null
    localRecommendations.value = null
    recommendationMessage.value = ''
    throw error
  } finally {
    loading.value = false
  }
}

function getCurrentPosition() {
  return new Promise<GeolocationPosition>((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('浏览器不支持定位。'))
      return
    }

    navigator.geolocation.getCurrentPosition(resolve, reject, {
      enableHighAccuracy: false,
      timeout: 5000,
      maximumAge: 10 * 60 * 1000,
    })
  })
}

function applyWeatherPayload(payload: WeatherSearchResponse) {
  if (payload.status === 'ambiguous') {
    candidates.value = payload.candidates
    activeCandidateIndex.value = payload.candidates.length ? 0 : -1
    districtCandidates.value = []
    weather.value = null
    localRecommendations.value = null
    recommendationMessage.value = ''
    errorMessage.value = payload.message
    return false
  }

  if (payload.status === 'unsupported' || !payload.weather) {
    districtCandidates.value = []
    weather.value = null
    localRecommendations.value = null
    recommendationMessage.value = ''
    errorMessage.value = payload.message
    return false
  }

  weather.value = payload.weather
  districtCandidates.value = payload.candidates.filter(
    (candidate) => candidate.id !== payload.weather?.location.id,
  )
  selectedCity.value = payload.weather.location.displayName
  cityQuery.value = payload.weather.location.name
  void loadRecommendations(payload.weather.location)
  return true
}

// 天气查询主流程：组装参数 -> 请求后端 -> 根据 resolved/ambiguous/unsupported 更新页面。
async function searchWeather(location?: LocationCandidate) {
  const query = location?.name ?? cityQuery.value.trim()
  if (!query) {
    return
  }

  loading.value = true
  errorMessage.value = ''
  candidates.value = []
  activeCandidateIndex.value = -1

  try {
    const params = new URLSearchParams({ city: query })
    if (location) {
      params.set('locationId', location.id)
    }

    const response = await fetch(`${apiBaseUrl}/api/weather/search?${params.toString()}`)
    if (!response.ok) {
      throw new Error(`天气查询失败，状态码 ${response.status}`)
    }

    const payload = (await response.json()) as WeatherSearchResponse
    if (!applyWeatherPayload(payload)) {
      return
    }

    await loadFavorites()
    await loadNotifications()
  } catch (error) {
    weather.value = null
    localRecommendations.value = null
    recommendationMessage.value = ''
    errorMessage.value = error instanceof Error ? error.message : '天气查询失败。'
  } finally {
    loading.value = false
  }
}

async function loadRecommendations(location: LocationCandidate) {
  const requestId = ++recommendationRequestId
  recommendationsLoading.value = true
  recommendationMessage.value = ''
  localRecommendations.value = null
  try {
    const params = new URLSearchParams({
      providerLocationId: location.id,
      displayName: location.displayName,
    })
    const response = await fetch(`${apiBaseUrl}/api/recommendations/local?${params.toString()}`, {
      headers: authHeaders(),
    })
    if (!response.ok) {
      throw new Error(`读取本地推荐失败，状态码 ${response.status}`)
    }
    const payload = (await response.json()) as LocalRecommendationResponse
    if (requestId !== recommendationRequestId) {
      return
    }
    localRecommendations.value = payload
    recommendationMessage.value = payload.message
  } catch (error) {
    if (requestId === recommendationRequestId) {
      recommendationMessage.value = error instanceof Error ? error.message : '本地推荐暂不可用。'
    }
  } finally {
    if (requestId === recommendationRequestId) {
      recommendationsLoading.value = false
    }
  }
}

async function refreshRecommendations() {
  if (!weather.value || !localRecommendations.value) {
    return
  }
  const requestId = ++recommendationRequestId
  recommendationsRefreshing.value = true
  recommendationMessage.value = ''
  try {
    const response = await fetch(`${apiBaseUrl}/api/recommendations/local/refresh`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        providerLocationId: weather.value.location.id,
        displayName: weather.value.location.displayName,
        currentBatchId: localRecommendations.value.batchId,
        displayedItemIds: displayedRecommendationIds.value,
      }),
    })
    if (!response.ok) {
      throw new Error(`换一批失败，状态码 ${response.status}`)
    }
    const payload = (await response.json()) as LocalRecommendationResponse
    if (requestId !== recommendationRequestId) {
      return
    }
    if (payload.foods.length && payload.places.length) {
      localRecommendations.value = payload
    }
    recommendationMessage.value = payload.message || '已换一批推荐。'
  } catch (error) {
    if (requestId === recommendationRequestId) {
      recommendationMessage.value = error instanceof Error ? error.message : '换一批失败，已保留当前推荐。'
    }
  } finally {
    if (requestId === recommendationRequestId) {
      recommendationsRefreshing.value = false
    }
  }
}

// 搜索框联想候选城市。requestId 用来丢弃较慢返回的旧请求，避免旧结果覆盖新输入。
async function suggestCities(query: string) {
  const requestId = ++suggestionRequestId

  try {
    const params = new URLSearchParams({ city: query })
    const response = await fetch(`${apiBaseUrl}/api/weather/search?${params.toString()}`)
    if (!response.ok || requestId !== suggestionRequestId) {
      return
    }

    const payload = (await response.json()) as WeatherSearchResponse
    if (requestId !== suggestionRequestId) {
      return
    }

    if (payload.status === 'ambiguous') {
      candidates.value = payload.candidates
      activeCandidateIndex.value = payload.candidates.length ? 0 : -1
      errorMessage.value = payload.message
      return
    }

    if (payload.status === 'resolved' && payload.weather && query.length > 1) {
      candidates.value = uniqueCandidates([payload.weather.location, ...payload.candidates])
      activeCandidateIndex.value = 0
      errorMessage.value = ''
      return
    }

    candidates.value = []
    activeCandidateIndex.value = -1
  } catch {
    candidates.value = []
    activeCandidateIndex.value = -1
  }
}

function uniqueCandidates(items: LocationCandidate[]) {
  const seen = new Set<string>()
  return items.filter((item) => {
    if (seen.has(item.id)) {
      return false
    }
    seen.add(item.id)
    return true
  })
}

// 风险提醒开关：优先尝试浏览器 Web Push，不支持或被拒绝时降级为站内提醒。
async function toggleNotifications(event: Event) {
  const input = event.target as HTMLInputElement
  notificationsEnabled.value = input.checked
  notificationMessage.value = ''

  if (!currentFavorite.value) {
    notificationMessage.value = '请先保存或选择一个收藏城市，再开启风险提醒。'
    notificationsEnabled.value = false
    return
  }

  if (!notificationsEnabled.value) {
    const subscription = currentNotification.value
    if (subscription) {
      await fetch(`${apiBaseUrl}/api/notifications/subscriptions/${subscription.id}`, {
        method: 'DELETE',
        headers: authHeaders(),
      })
    }
    await loadNotifications()
    notificationMessage.value = '此城市风险提醒已关闭。'
    await loadNotificationEvents()
    return
  }

  const browserPermission =
    'Notification' in window ? await Notification.requestPermission() : 'unsupported'

  if (browserPermission !== 'granted' || !webPushPublicKey.value || !('serviceWorker' in navigator)) {
    await saveNotificationSubscription({
      channel: 'in_app',
      permissionStatus: browserPermission === 'granted' ? 'unsupported' : browserPermission,
      pushEndpoint: '',
      pushP256dh: '',
      pushAuth: '',
    })
    notificationsEnabled.value = false
    notificationMessage.value =
      browserPermission === 'denied'
        ? '浏览器通知权限已被拒绝，仍可使用站内风险提醒。'
        : '当前环境暂不支持后台推送，仍可使用站内风险提醒。'
    await loadNotificationEvents()
    return
  }

  const registration = await navigator.serviceWorker.register('/sw.js')
  const pushSubscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64ToUint8Array(webPushPublicKey.value),
  })
  const keys = pushSubscription.toJSON().keys

  await saveNotificationSubscription({
    channel: 'web_push',
    permissionStatus: browserPermission,
    pushEndpoint: pushSubscription.endpoint,
    pushP256dh: keys?.p256dh ?? '',
    pushAuth: keys?.auth ?? '',
  })
  notificationMessage.value = '此城市已开启浏览器风险提醒。'
  await loadNotificationEvents()
}

async function saveNotificationSubscription(input: {
  channel: 'web_push' | 'in_app'
  permissionStatus: NotificationPermission | 'unsupported'
  pushEndpoint: string
  pushP256dh: string
  pushAuth: string
}) {
  if (!currentFavorite.value) {
    return
  }

  const response = await fetch(`${apiBaseUrl}/api/notifications/subscriptions`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({
      favoriteCityId: currentFavorite.value.id,
      channel: input.channel,
      permissionStatus: input.permissionStatus,
      pushEndpoint: input.pushEndpoint,
      pushP256dh: input.pushP256dh,
      pushAuth: input.pushAuth,
      dailySummaryEnabled: true,
      severeWeatherEnabled: true,
      cooldownMinutes: 1,
    }),
  })

  if (!response.ok) {
    notificationMessage.value = `保存通知设置失败，状态码 ${response.status}`
    notificationsEnabled.value = false
    return
  }
  await loadNotifications()
}

// 手动触发一次风险评估，适合演示“根据天气规则生成提醒”的流程。
async function evaluateNotifications() {
  if (!userId.value) {
    return
  }

  notificationsEvaluating.value = true
  notificationMessage.value = ''
  try {
    const response = await fetch(`${apiBaseUrl}/api/notifications/subscriptions/evaluate`, {
      method: 'POST',
      headers: authHeaders(),
    })
    if (!response.ok) {
      throw new Error(`评估风险提醒失败，状态码 ${response.status}`)
    }
    const payload = (await response.json()) as NotificationEvaluationResponse
    notificationEvents.value = [...payload.events, ...notificationEvents.value].slice(0, 50)
    notificationMessage.value =
      payload.generated > 0
        ? `已生成 ${payload.generated} 条风险提醒，合并 ${payload.suppressed} 条重复提醒。`
        : `暂无新的天气风险，合并 ${payload.suppressed} 条重复提醒。`
    await loadNotifications()
    await loadNotificationEvents()
  } catch (error) {
    notificationMessage.value = error instanceof Error ? error.message : '评估风险提醒失败。'
  } finally {
    notificationsEvaluating.value = false
  }
}

async function testNotificationPush() {
  if (!userId.value) {
    return
  }

  notificationTesting.value = true
  notificationMessage.value = ''
  try {
    const response = await fetch(`${apiBaseUrl}/api/notifications/subscriptions/test`, {
      method: 'POST',
      headers: authHeaders(),
    })
    if (!response.ok) {
      throw new Error(`测试推送失败，状态码 ${response.status}`)
    }
    const payload = (await response.json()) as NotificationEvaluationResponse
    notificationEvents.value = [...payload.events, ...notificationEvents.value].slice(0, 50)
    notificationMessage.value =
      currentNotification.value?.channel === 'web_push'
        ? '测试提醒已发送到浏览器后台通道。'
        : '测试提醒已发送到站内通知通道。'
    await loadNotificationEvents()
  } catch (error) {
    notificationMessage.value = error instanceof Error ? error.message : '测试提醒失败。'
  } finally {
    notificationTesting.value = false
  }
}

async function deleteNotificationEvent(event: NotificationEvent) {
  if (!userId.value) {
    return
  }

  const response = await fetch(`${apiBaseUrl}/api/notifications/events/${event.id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  })
  if (!response.ok) {
    notificationMessage.value = `删除提醒失败，状态码 ${response.status}`
    return
  }
  notificationEvents.value = notificationEvents.value.filter((item) => item.id !== event.id)
  notificationMessage.value = '提醒记录已删除。'
}

// 浏览器 Push API 要求把 VAPID 公钥从 base64 字符串转成 Uint8Array。
function urlBase64ToUint8Array(value: string) {
  const padding = '='.repeat((4 - (value.length % 4)) % 4)
  const base64 = (value + padding).replace(/-/g, '+').replace(/_/g, '/')
  const rawData = window.atob(base64)
  const output = new Uint8Array(rawData.length)
  for (let index = 0; index < rawData.length; index += 1) {
    output[index] = rawData.charCodeAt(index)
  }
  return output
}

function formatNumber(value: number | null | undefined, suffix: string) {
  if (value === null || value === undefined) {
    return '--'
  }
  return `${Math.round(value)} ${suffix}`
}

function formatUpdated(value: string | null | undefined) {
  if (!value) {
    return '不可用'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function weatherStatusText(status: FavoriteCity['weatherStatus']) {
  const labels: Record<FavoriteCity['weatherStatus'], string> = {
    available: '可用',
    stale: '已过期',
    unavailable: '不可用',
  }
  return labels[status]
}

function eventTypeText(type: NotificationEvent['eventType']) {
  const labels: Record<NotificationEvent['eventType'], string> = {
    severe_weather: '天气风险',
    daily_summary: '每日摘要',
    weather_change: '温度/风力',
  }
  return labels[type]
}

function recommendationImageFallback(event: Event) {
  const image = event.target as HTMLImageElement
  image.removeAttribute('src')
  image.classList.add('image-fallback')
}

// 调用后端 AI 助手接口，把当前天气数据作为上下文一起发给后端。
async function askAssistant() {
  const message = assistantQuestion.value.trim()
  if (!message) {
    return
  }

  await ensureAnonymousSession()
  assistantLoading.value = true
  assistantMessage.value = ''
  assistantQuestion.value = ''
  chatMessages.value.push({ id: Date.now(), role: 'user', content: message })

  try {
    const response = await fetch(`${apiBaseUrl}/api/assistant/messages`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({
        message,
        weatherContext: weather.value,
      }),
    })
    if (!response.ok) {
      throw new Error(`助手请求失败，状态码 ${response.status}`)
    }

    const payload = (await response.json()) as AssistantResponse
    chatMessages.value.push({ id: Date.now() + 1, role: 'assistant', content: payload.answer })
  } catch (error) {
    assistantMessage.value = error instanceof Error ? error.message : '助手请求失败。'
    chatMessages.value.push({
      id: Date.now() + 1,
      role: 'assistant',
      content: assistantMessage.value,
    })
  } finally {
    assistantLoading.value = false
  }
}

function startAssistantButtonDrag(event: PointerEvent) {
  const target = event.currentTarget as HTMLElement
  const rect = target.getBoundingClientRect()
  assistantDragStart = {
    pointerId: event.pointerId,
    x: event.clientX,
    y: event.clientY,
    left: rect.left,
    top: rect.top,
    moved: false,
  }
  target.setPointerCapture(event.pointerId)
}

function dragAssistantButton(event: PointerEvent) {
  if (!assistantDragStart || assistantDragStart.pointerId !== event.pointerId) {
    return
  }

  const deltaX = event.clientX - assistantDragStart.x
  const deltaY = event.clientY - assistantDragStart.y
  if (Math.hypot(deltaX, deltaY) > 4) {
    assistantDragStart.moved = true
  }

  if (!assistantDragStart.moved) {
    return
  }

  const buttonSize = 56
  const margin = 8
  assistantButtonLeft.value = Math.min(
    Math.max(assistantDragStart.left + deltaX, margin),
    window.innerWidth - buttonSize - margin,
  )
  assistantButtonTop.value = Math.min(
    Math.max(assistantDragStart.top + deltaY, margin),
    window.innerHeight - buttonSize - margin,
  )
  assistantButtonMoved.value = true
}

function finishAssistantButtonDrag(event: PointerEvent) {
  if (!assistantDragStart || assistantDragStart.pointerId !== event.pointerId) {
    return
  }

  const wasDragged = assistantDragStart.moved
  const target = event.currentTarget as HTMLElement
  if (target.hasPointerCapture(event.pointerId)) {
    target.releasePointerCapture(event.pointerId)
  }
  assistantDragStart = undefined

  if (!wasDragged) {
    assistantOpen.value = !assistantOpen.value
  }
}
</script>

<template>
  <!-- 页面主体分为：顶部搜索、账户、当前天气、收藏、预报、助手、风险提醒和提醒记录。 -->
  <main class="shell">
    <section class="toolbar" aria-label="城市搜索">
      <div>
        <p class="eyebrow">智能天气</p>
        <h1>{{ selectedCity }}</h1>
      </div>

      <form class="search" @submit.prevent="submitSearch">
        <label for="city-search">城市</label>
        <div class="search-row">
          <div class="search-combobox">
            <input
              id="city-search"
              v-model="cityQuery"
              type="search"
              role="combobox"
              placeholder="搜索城市、拼音或英文名"
              autocomplete="off"
              aria-controls="city-options"
              :aria-expanded="candidates.length > 0"
              :aria-activedescendant="
                activeCandidateIndex >= 0 ? `city-option-${candidates[activeCandidateIndex]?.id}` : undefined
              "
              @keydown.down.prevent="moveCandidate(1)"
              @keydown.up.prevent="moveCandidate(-1)"
              @keydown.enter.prevent="submitSearch"
            />
            <div v-if="candidates.length" id="city-options" class="search-options" role="listbox">
              <button
                v-for="(candidate, index) in candidates"
                :id="`city-option-${candidate.id}`"
                :key="candidate.id"
                type="button"
                role="option"
                :aria-selected="activeCandidateIndex === index"
                :class="{ active: activeCandidateIndex === index }"
                @mouseenter="activeCandidateIndex = index"
                @mousedown.prevent="selectCandidate(candidate)"
              >
                <span>{{ candidate.name }}</span>
                <small>{{ candidate.displayName }}</small>
              </button>
            </div>
          </div>
          <button type="submit" :disabled="!canSearch">搜索</button>
          <div class="account-menu">
            <button
              type="button"
              class="account-trigger"
              :aria-expanded="authMenuOpen"
              aria-controls="account-card"
              @click="authMenuOpen = !authMenuOpen"
            >
              <svg aria-hidden="true" viewBox="0 0 24 24">
                <path
                  d="M20 21a8 8 0 0 0-16 0M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
              <span>账号</span>
            </button>

            <section v-if="authMenuOpen" id="account-card" class="account-card" aria-label="用户账户">
              <div class="account-card-header">
                <div>
                  <h2>{{ currentUser ? currentUser.displayName : '账号登录' }}</h2>
                  <p>
                    {{
                      currentUser
                        ? `${currentUser.email} 的收藏和风险提醒已同步。`
                        : '登录后同步收藏、风险提醒和助手记录。'
                    }}
                  </p>
                </div>
                <button
                  type="button"
                  class="icon-button"
                  aria-label="关闭账号面板"
                  @click="authMenuOpen = false"
                >
                  ×
                </button>
              </div>

              <form v-if="!currentUser" class="auth-form" @submit.prevent="submitAuth">
                <div class="auth-mode" role="tablist" aria-label="账户模式">
                  <button
                    type="button"
                    :class="{ active: authMode === 'login' }"
                    @click="authMode = 'login'"
                  >
                    登录
                  </button>
                  <button
                    type="button"
                    :class="{ active: authMode === 'register' }"
                    @click="authMode = 'register'"
                  >
                    注册
                  </button>
                </div>
                <input v-model="authEmail" type="email" placeholder="邮箱" autocomplete="email" />
                <input
                  v-model="authPassword"
                  type="password"
                  placeholder="密码"
                  autocomplete="current-password"
                />
                <input
                  v-if="authMode === 'register'"
                  v-model="authDisplayName"
                  type="text"
                  placeholder="显示名称"
                  autocomplete="name"
                />
                <button type="submit" :disabled="authLoading">
                  {{ authLoading ? '处理中' : authMode === 'login' ? '登录' : '注册并登录' }}
                </button>
              </form>
              <button v-else type="button" class="secondary-button account-logout" @click="logout">
                退出登录
              </button>
              <p v-if="authMessage" class="auth-message" role="status">{{ authMessage }}</p>
            </section>
          </div>
        </div>
      </form>
    </section>

    <section class="dashboard" aria-label="天气工作台">
      <article class="current-weather" aria-label="当前天气">
        <div class="weather-main">
          <span class="temperature">{{
            current?.temperatureCelsius === null || current?.temperatureCelsius === undefined
              ? '--'
              : Math.round(current.temperatureCelsius)
          }}</span>
          <span class="unit">℃</span>
        </div>
        <p class="status" role="status" aria-live="polite">
          <span v-if="loading">正在加载天气数据...</span>
          <span v-else-if="weather">{{ weather.current.condition }}，来源：{{ weather.sourceName }}</span>
          <span v-else-if="errorMessage">{{ errorMessage }}</span>
          <span v-else>搜索城市后显示当前天气。</span>
        </p>
        <div v-if="districtCandidates.length" class="district-picker" aria-label="区县切换">
          <span>切换区县</span>
          <div class="district-list">
            <button
              v-for="district in districtCandidates"
              :key="district.id"
              type="button"
              class="secondary-button"
              @click="selectCandidate(district)"
            >
              {{ district.name }}
            </button>
          </div>
        </div>
        <dl class="metrics">
          <div>
            <dt>湿度</dt>
            <dd>{{ formatNumber(current?.humidityPercent, '%') }}</dd>
          </div>
          <div>
            <dt>风速</dt>
            <dd>{{ formatNumber(current?.windSpeedKmh, 'km/h') }}</dd>
          </div>
          <div>
            <dt>降水</dt>
            <dd>{{ formatNumber(current?.precipitationMm, 'mm') }}</dd>
          </div>
          <div>
            <dt>更新时间</dt>
            <dd>{{ formatUpdated(current?.time) }}</dd>
          </div>
        </dl>
      </article>

      <aside class="favorites" aria-label="收藏城市">
        <div class="panel-heading">
          <h2>收藏城市</h2>
          <button
            v-if="weather && !currentFavorite"
            type="button"
            aria-label="添加当前城市到收藏"
            :disabled="favoritesLoading"
            @click="addCurrentFavorite"
          >
            添加
          </button>
          <button
            v-else-if="currentFavorite"
            type="button"
            class="secondary-button"
            aria-label="从收藏中移除当前城市"
            :disabled="favoritesLoading"
            @click="removeFavorite(currentFavorite)"
          >
            移除
          </button>
        </div>
        <div class="favorite-list">
          <p v-if="favoritesLoading" class="favorite-state">正在加载收藏城市...</p>
          <p v-else-if="favoriteMessage" class="favorite-state">{{ favoriteMessage }}</p>
          <p v-else-if="!favorites.length" class="favorite-state">保存已解析城市后会显示在这里。</p>
          <div v-for="favorite in favorites" :key="favorite.id" class="favorite-row">
            <button
              type="button"
              :class="{ active: selectedCity === favorite.displayName }"
              @click="selectFavorite(favorite)"
            >
              <span>{{ favorite.displayName }}</span>
              <small>
                {{
                  !favorite.weatherAvailable ||
                  favorite.latestTemperatureCelsius === null ||
                  favorite.latestTemperatureCelsius === undefined
                    ? weatherStatusText(favorite.weatherStatus)
                    : `${Math.round(favorite.latestTemperatureCelsius)}℃`
                }}
              </small>
            </button>
            <button
              type="button"
              class="icon-button"
              :aria-label="`从收藏中移除 ${favorite.displayName}`"
              @click="removeFavorite(favorite)"
            >
              X
            </button>
          </div>
        </div>
      </aside>

      <section class="forecast" aria-label="天气预报">
        <div class="panel-heading">
          <h2>天气预报</h2>
          <span>公制单位</span>
        </div>
        <div class="forecast-grid">
          <article v-for="item in forecast" :key="item.date" class="forecast-item">
            <h3>{{ item.date }}</h3>
            <p>{{ item.condition }}</p>
            <dl>
              <div>
                <dt>最高</dt>
                <dd>{{ formatNumber(item.highCelsius, '℃') }}</dd>
              </div>
              <div>
                <dt>最低</dt>
                <dd>{{ formatNumber(item.lowCelsius, '℃') }}</dd>
              </div>
              <div>
                <dt>降水</dt>
                <dd>{{ formatNumber(item.precipitationProbabilityPercent, '%') }}</dd>
              </div>
            </dl>
          </article>
          <p v-if="!forecast.length" class="empty-forecast">解析城市后会显示预报。</p>
        </div>
      </section>

      <section class="recommendations" aria-label="本地推荐">
        <div class="panel-heading recommendation-heading">
          <div>
            <h2>本地推荐</h2>
            <span>
              {{
                recommendationsLoading
                  ? '正在生成当地推荐'
                  : recommendationsRefreshing
                    ? '正在换一批'
                    : recommendationMessage || (localRecommendations ? '根据当前地区生成' : '搜索地区后显示')
              }}
            </span>
          </div>
          <button
            type="button"
            class="refresh-button"
            aria-label="换一批本地推荐"
            :disabled="!canRefreshRecommendations"
            @click="refreshRecommendations"
          >
            <span>{{ recommendationsRefreshing ? '刷新中' : '换一批' }}</span>
            <svg aria-hidden="true" viewBox="0 0 24 24" focusable="false">
              <path
                d="M20 11a8.1 8.1 0 0 0-14.3-4.9L4 8m0 0h5M4 8V3m0 10a8.1 8.1 0 0 0 14.3 4.9L20 16m0 0h-5m5 0v5"
                fill="none"
                stroke="currentColor"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
              />
            </svg>
          </button>
        </div>

        <div class="recommendation-groups">
          <p v-if="recommendationsLoading" class="recommendation-state" role="status">
            正在加载美食和游玩地点推荐...
          </p>
          <p v-else-if="!weather" class="recommendation-state">
            搜索并解析地区后，这里会显示 5 个美食和 5 个游玩地点。
          </p>
          <p v-else-if="recommendationMessage && !localRecommendations" class="recommendation-state" role="status">
            {{ recommendationMessage }}
          </p>
          <section class="recommendation-group" aria-labelledby="food-preview-title">
            <div class="recommendation-group-title">
              <p class="eyebrow">Food</p>
              <h3 id="food-preview-title">美食推荐</h3>
            </div>
            <ol class="recommendation-list">
              <li v-for="item in recommendationFoods" :key="item.id" class="recommendation-card">
                <div class="recommendation-image">
                  <img :src="item.imageUrl" :alt="item.imageAlt" @error="recommendationImageFallback" />
                </div>
                <div class="recommendation-body">
                  <div class="recommendation-title">
                    <span class="recommendation-rank">{{ item.rank }}</span>
                    <h4>{{ item.name }}</h4>
                  </div>
                  <p>{{ item.description }}</p>
                  <a :href="item.sourceUrl" target="_blank" rel="noreferrer">
                    来源：{{ item.sourceTitle }}
                  </a>
                </div>
              </li>
              <li v-if="!recommendationsLoading && weather && !recommendationFoods.length" class="recommendation-state">
                暂无可展示的美食推荐。
              </li>
            </ol>
          </section>

          <section class="recommendation-group" aria-labelledby="place-preview-title">
            <div class="recommendation-group-title">
              <p class="eyebrow">Travel</p>
              <h3 id="place-preview-title">游玩地点</h3>
            </div>
            <ol class="recommendation-list">
              <li v-for="item in recommendationPlaces" :key="item.id" class="recommendation-card">
                <div class="recommendation-image">
                  <img :src="item.imageUrl" :alt="item.imageAlt" @error="recommendationImageFallback" />
                </div>
                <div class="recommendation-body">
                  <div class="recommendation-title">
                    <span class="recommendation-rank">{{ item.rank }}</span>
                    <h4>{{ item.name }}</h4>
                  </div>
                  <p>{{ item.description }}</p>
                  <a :href="item.sourceUrl" target="_blank" rel="noreferrer">
                    来源：{{ item.sourceTitle }}
                  </a>
                </div>
              </li>
              <li v-if="!recommendationsLoading && weather && !recommendationPlaces.length" class="recommendation-state">
                暂无可展示的游玩地点推荐。
              </li>
            </ol>
          </section>
        </div>
      </section>

      <section class="notifications" aria-label="通知设置">
        <div>
          <h2>通知设置</h2>
          <p id="notification-status" role="status">
            {{ notificationMessage || (notificationsStreaming ? '实时同步已连接。' : '系统会根据收藏城市天气判断是否需要提醒。') }}
          </p>
          <dl class="notification-status-grid">
            <div>
              <dt>提醒通道</dt>
              <dd>{{ notificationChannelText }}</dd>
            </div>
            <div>
              <dt>浏览器权限</dt>
              <dd>{{ browserNotificationText }}</dd>
            </div>
            <div>
              <dt>Web Push</dt>
              <dd>{{ webPushConfigured ? '已配置' : '未配置' }}</dd>
            </div>
            <div>
              <dt>风险扫描</dt>
              <dd>1 分钟</dd>
            </div>
          </dl>
        </div>
        <div class="notification-actions">
          <button
            type="button"
            class="secondary-button"
            :disabled="notificationsEvaluating || !favorites.length"
            @click="evaluateNotifications"
          >
            {{ notificationsEvaluating ? '评估中' : '立即评估' }}
          </button>
          <button
            type="button"
            class="secondary-button"
            :disabled="notificationTesting || !currentNotification"
            @click="testNotificationPush"
          >
            {{ notificationTesting ? '发送中' : '测试提醒' }}
          </button>
          <label class="switch">
            <input
              :checked="Boolean(currentNotification) && notificationsEnabled"
              type="checkbox"
              :disabled="!currentFavorite"
              aria-describedby="notification-status"
              @change="toggleNotifications"
            />
            <span>开启风险提醒</span>
          </label>
        </div>
      </section>

      <section class="notification-events" aria-label="天气风险提醒记录">
        <div class="panel-heading">
          <h2>提醒记录</h2>
          <span>{{ notificationEvents.length ? `最近 ${notificationEvents.length} 条` : '暂无记录' }}</span>
        </div>
        <div class="event-list">
          <article v-for="event in notificationEvents" :key="event.id" class="event-item">
            <div>
              <span class="event-kind">{{ eventTypeText(event.eventType) }}</span>
              <h3>{{ event.title }}</h3>
              <p>{{ event.body }}</p>
            </div>
            <div class="event-actions">
              <small>{{ formatUpdated(event.createdAt) }}</small>
              <button
                type="button"
                class="icon-button"
                :aria-label="`删除提醒：${event.title}`"
                @click="deleteNotificationEvent(event)"
              >
                X
              </button>
            </div>
          </article>
          <p v-if="!notificationEvents.length" class="empty-events">
            开启收藏城市风险提醒后，可手动评估并查看生成记录。
          </p>
        </div>
      </section>
    </section>

    <button
      type="button"
      class="assistant-float-button"
      :class="{ active: assistantOpen, moved: assistantButtonMoved }"
      :style="assistantButtonStyle"
      :aria-expanded="assistantOpen"
      aria-controls="assistant-floating-panel"
      aria-label="天气助手"
      @pointerdown="startAssistantButtonDrag"
      @pointermove="dragAssistantButton"
      @pointerup="finishAssistantButtonDrag"
      @pointercancel="finishAssistantButtonDrag"
    >
      <svg aria-hidden="true" viewBox="0 0 24 24">
        <path
          d="M7 18h10a4 4 0 0 0 .6-8A6 6 0 0 0 6.1 8.3 4.8 4.8 0 0 0 7 18Z"
          fill="none"
          stroke="currentColor"
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
        />
        <path
          d="M16 3v2M21 8h-2M19.1 4.9l-1.4 1.4"
          fill="none"
          stroke="currentColor"
          stroke-linecap="round"
          stroke-width="2"
        />
      </svg>
    </button>

    <section
      v-if="assistantOpen"
      id="assistant-floating-panel"
      class="assistant-floating-panel"
      aria-label="AI 天气助手"
    >
      <div class="assistant-floating-heading">
        <div>
          <h2>天气助手</h2>
          <span>{{ weather ? '已使用当前天气上下文' : '暂无实时数据' }}</span>
        </div>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭天气助手"
          @click="assistantOpen = false"
        >
          X
        </button>
      </div>
      <div class="messages" role="log" aria-live="polite">
        <article v-for="message in chatMessages" :key="message.id" :class="['chat-message', message.role]">
          <span>{{ message.role === 'user' ? '你' : '助手' }}</span>
          <p>{{ message.content }}</p>
        </article>
        <p v-if="assistantLoading" class="assistant-state" role="status">
          助手正在生成回答...
        </p>
      </div>
      <form class="assistant-form" @submit.prevent="askAssistant">
        <label for="assistant-question">问题</label>
        <textarea
          id="assistant-question"
          v-model="assistantQuestion"
          rows="3"
          placeholder="询问出行、穿衣、景点安排或天气建议"
        />
        <button type="submit" :disabled="assistantLoading || assistantQuestion.trim().length === 0">
          提问
        </button>
      </form>
    </section>
  </main>
</template>
