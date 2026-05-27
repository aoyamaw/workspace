import { expect, test } from '@playwright/test'

function recommendationPayload(prefix = '上海') {
  const item = (category: 'food' | 'place', index: number) => ({
    id: `${category}-${prefix}-${index}`,
    category,
    rank: index,
    name: `${prefix}${category === 'food' ? '美食' : '地点'}${index}`,
    description: `第 ${index} 个本地${category === 'food' ? '美食' : '游玩地点'}推荐。`,
    imageUrl: `https://example.com/${category}-${index}.jpg`,
    imageAlt: `${prefix}${category === 'food' ? '美食' : '地点'}${index}插图`,
    sourceTitle: `${prefix}公开资料${index}`,
    sourceUrl: 'https://example.com/source',
    batchId: `${prefix}-batch`,
  })
  return {
    batchId: `${prefix}-batch`,
    providerLocationId: 'shanghai-cn',
    displayName: '上海，中国',
    foods: [1, 2, 3, 4, 5].map((index) => item('food', index)),
    places: [1, 2, 3, 4, 5].map((index) => item('place', index)),
    hasMore: true,
    message: '',
    generatedAt: new Date().toISOString(),
  }
}

function forecastPayload(prefix = '北京') {
  return [
    {
      date: '2026-05-27',
      highCelsius: 29,
      lowCelsius: 20,
      precipitationProbabilityPercent: 8,
      weatherCode: 1,
      condition: '晴',
    },
    {
      date: '2026-05-28',
      highCelsius: 25,
      lowCelsius: 18,
      precipitationProbabilityPercent: 64,
      weatherCode: 61,
      condition: `${prefix}小雨`,
    },
    {
      date: '2026-05-29',
      highCelsius: 27,
      lowCelsius: 19,
      precipitationProbabilityPercent: 22,
      weatherCode: 3,
      condition: '多云',
    },
  ]
}

test.beforeEach(async ({ page }) => {
  await page.context().grantPermissions(['geolocation'])
  await page.context().setGeolocation({ latitude: 39.9042, longitude: 116.4074 })

  await page.route('http://localhost:8080/api/**', async (route) => {
    const url = new URL(route.request().url())

    if (url.pathname === '/api/sessions/anonymous') {
      await route.fulfill({
        json: {
          userId: '00000000-0000-4000-8000-000000000001',
          sessionId: '00000000-0000-4000-8000-000000000002',
          identityType: 'anonymous',
          createdAt: new Date().toISOString(),
        },
      })
      return
    }

    if (url.pathname === '/api/favorites' || url.pathname === '/api/notifications/subscriptions') {
      await route.fulfill({ json: [] })
      return
    }

    if (url.pathname === '/api/notifications/subscriptions/events') {
      await route.fulfill({ json: [] })
      return
    }

    if (url.pathname === '/api/notifications/subscriptions/stream') {
      await route.fulfill({
        contentType: 'text/event-stream',
        body: 'event: notification-subscriptions\ndata: []\n\n',
      })
      return
    }

    if (url.pathname === '/api/recommendations/local' && route.request().method() === 'GET') {
      await route.fulfill({ json: recommendationPayload(url.searchParams.get('displayName')?.split(',')[0] ?? '上海') })
      return
    }

    if (url.pathname === '/api/recommendations/local/refresh') {
      await route.fulfill({ json: recommendationPayload('新一批') })
      return
    }

    if (url.pathname === '/api/weather/search') {
      await route.fulfill({
        json: {
          query: url.searchParams.get('city'),
          status: 'resolved',
          message: 'resolved',
          candidates: [],
          weather: {
            location: {
              id: 'shanghai-cn',
              name: '上海',
              displayName: '上海，中国',
              country: '中国',
              countryCode: 'CN',
              admin1: null,
              timezone: 'Asia/Shanghai',
              latitude: 31.2304,
              longitude: 121.4737,
            },
            current: {
              time: new Date().toISOString(),
              temperatureCelsius: 22,
              humidityPercent: 68,
              windSpeedKmh: 12,
              windDirectionDegrees: 90,
              precipitationMm: 0,
              weatherCode: 1,
              condition: '少云',
            },
            forecast: forecastPayload('上海'),
            sourceName: 'MockWeather',
            timezone: 'Asia/Shanghai',
            retrievedAt: new Date().toISOString(),
          },
        },
      })
      return
    }

    if (url.pathname === '/api/weather/nearby') {
      await route.fulfill({
        json: {
          query: '北京',
          status: 'resolved',
          message: 'resolved',
          candidates: [],
          weather: {
            location: {
              id: 'beijing-cn',
              name: '北京',
              displayName: '北京, 北京市, 中国',
              country: '中国',
              countryCode: 'CN',
              admin1: '北京市',
              timezone: 'Asia/Shanghai',
              latitude: Number(url.searchParams.get('latitude')),
              longitude: Number(url.searchParams.get('longitude')),
            },
            current: {
              time: new Date().toISOString(),
              temperatureCelsius: 18,
              humidityPercent: 45,
              windSpeedKmh: 9,
              windDirectionDegrees: 120,
              precipitationMm: 0,
              weatherCode: 1,
              condition: '晴',
            },
            forecast: forecastPayload('北京'),
            sourceName: 'MockWeather',
            timezone: 'Asia/Shanghai',
            retrievedAt: new Date().toISOString(),
          },
        },
      })
      return
    }

    await route.fulfill({ status: 404, json: { message: 'Not mocked' } })
  })
})

test('dashboard fits without horizontal overflow', async ({ page }, testInfo) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { name: '北京, 北京市, 中国' })).toBeVisible()
  await expect(page.getByRole('combobox', { name: '城市' })).toBeVisible()
  await expect(page.getByRole('button', { name: '账号' })).toBeVisible()
  await page.getByRole('button', { name: '账号' }).click()
  await expect(page.getByRole('heading', { name: '账号登录' })).toBeVisible()
  await expect(page.getByPlaceholder('邮箱')).toBeVisible()
  await expect(page.getByRole('heading', { name: '收藏城市' })).toBeVisible()
  await expect(page.getByRole('button', { name: '天气助手' })).toBeVisible()
  await page.getByRole('button', { name: '天气助手' }).click()
  await expect(page.getByRole('heading', { name: '天气助手' })).toBeVisible()
  await expect(page.getByPlaceholder('询问出行、穿衣、景点安排或天气建议')).toBeVisible()
  await expect(page.getByRole('heading', { name: '本地推荐' })).toBeVisible()
  await expect(page.getByText('北京小雨')).toBeVisible()
  await expect(page.getByRole('heading', { name: '美食推荐' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '游玩地点' })).toBeVisible()
  await expect(page.getByRole('button', { name: '换一批本地推荐' })).toBeVisible()
  await expect(page.getByText('北京美食1')).toBeVisible()
  await expect(page.getByText('北京地点5')).toBeVisible()
  await expect(page.getByRole('checkbox', { name: '开启风险提醒' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '提醒记录' })).toBeVisible()

  const hasHorizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
  expect(hasHorizontalOverflow).toBe(false)

  await page.screenshot({
    path: `test-results/layout-${testInfo.project.name}.png`,
    fullPage: true,
  })
})

test('recommendations can refresh without losing the current weather', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByText('北京美食1')).toBeVisible()
  await page.getByRole('button', { name: '换一批本地推荐' }).click()
  await expect(page.getByText('新一批美食1')).toBeVisible()
  await expect(page.getByRole('heading', { name: '北京, 北京市, 中国' })).toBeVisible()
})

test('initial Shanghai fallback keeps recommendation cards loading until data arrives', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'geolocation', {
      value: {
        getCurrentPosition: (
          _success: PositionCallback,
          error?: PositionErrorCallback,
        ) => {
          error?.({
            code: 1,
            message: 'Permission denied',
            PERMISSION_DENIED: 1,
            POSITION_UNAVAILABLE: 2,
            TIMEOUT: 3,
          })
        },
      },
      configurable: true,
    })
  })

  let resolveRecommendations: (() => void) | undefined
  const recommendationsReady = new Promise<void>((resolve) => {
    resolveRecommendations = resolve
  })

  await page.route('http://localhost:8080/api/recommendations/local?**', async (route) => {
    await recommendationsReady
    const url = new URL(route.request().url())
    await route.fulfill({
      json: recommendationPayload(url.searchParams.get('displayName')?.split(',')[0] ?? '上海'),
    })
  })

  await page.goto('/')

  await expect(page.getByRole('heading', { name: '上海，中国' })).toBeVisible()
  await expect(page.getByText('正在加载美食和游玩地点推荐...')).toBeVisible()
  await expect(page.getByText('暂无可展示的美食推荐。')).toHaveCount(0)
  await expect(page.getByText('暂无可展示的游玩地点推荐。')).toHaveCount(0)

  resolveRecommendations?.()
  await expect(page.getByText('上海，中国美食1')).toBeVisible()
  await expect(page.getByText('上海，中国地点5')).toBeVisible()
})

test('initial recommendations recover when user-scoped response is empty', async ({ page }) => {
  let emptyUserScopedResponses = 0

  await page.route('http://localhost:8080/api/recommendations/local?**', async (route) => {
    const userId = route.request().headers()['x-user-id']
    if (userId) {
      emptyUserScopedResponses += 1
      await route.fulfill({
        json: {
          batchId: 'empty-user-batch',
          providerLocationId: 'beijing-cn',
          displayName: '北京, 北京市, 中国',
          foods: [],
          places: [],
          hasMore: false,
          message: '暂无更多不重复推荐。',
          generatedAt: new Date().toISOString(),
        },
      })
      return
    }

    await route.fulfill({ json: recommendationPayload('北京') })
  })

  await page.goto('/')

  await expect(page.getByText('北京美食1')).toBeVisible()
  await expect(page.getByText('北京地点5')).toBeVisible()
  expect(emptyUserScopedResponses).toBeGreaterThan(0)
})
