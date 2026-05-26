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

test.beforeEach(async ({ page }) => {
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
      await route.fulfill({ json: recommendationPayload() })
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
            forecast: [],
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

  await expect(page.getByRole('heading', { name: '上海，中国' })).toBeVisible()
  await expect(page.getByRole('combobox', { name: '城市' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '收藏城市' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '天气助手' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '本地推荐' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '美食推荐' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '游玩地点' })).toBeVisible()
  await expect(page.getByRole('button', { name: '换一批本地推荐' })).toBeVisible()
  await expect(page.getByText('上海美食1')).toBeVisible()
  await expect(page.getByText('上海地点5')).toBeVisible()
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

  await expect(page.getByText('上海美食1')).toBeVisible()
  await page.getByRole('button', { name: '换一批本地推荐' }).click()
  await expect(page.getByText('新一批美食1')).toBeVisible()
  await expect(page.getByRole('heading', { name: '上海，中国' })).toBeVisible()
})
