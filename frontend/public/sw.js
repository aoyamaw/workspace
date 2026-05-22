self.addEventListener('push', (event) => {
  let payload = {
    title: '天气更新',
    body: '有新的天气信息可查看。',
  }

  if (event.data) {
    try {
      payload = event.data.json()
    } catch (error) {
      payload.body = event.data.text()
    }
  }

  event.waitUntil(
    self.registration.showNotification(payload.title || '天气更新', {
      body: payload.body || '有新的天气信息可查看。',
      tag: 'weather-update',
    }),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  event.waitUntil(clients.openWindow('/'))
})
