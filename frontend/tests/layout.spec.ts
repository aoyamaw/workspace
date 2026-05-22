import { expect, test } from '@playwright/test'

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
  await expect(page.getByRole('checkbox', { name: '后台推送' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '通知记录' })).toBeVisible()

  const hasHorizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
  expect(hasHorizontalOverflow).toBe(false)

  await page.screenshot({
    path: `test-results/layout-${testInfo.project.name}.png`,
    fullPage: true,
  })
})
