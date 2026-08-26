import storage from '@system.storage'

const KEY_PREFIX = 'ai_quota_'

export default {
  get(key, fallback) {
    return new Promise((resolve) => {
      storage.get({
        key: KEY_PREFIX + key,
        success: (data) => {
          try {
            resolve(JSON.parse(data.value))
          } catch (e) {
            resolve(fallback)
          }
        },
        fail: () => resolve(fallback)
      })
    })
  },
  set(key, value) {
    return new Promise((resolve) => {
      storage.set({
        key: KEY_PREFIX + key,
        value: JSON.stringify(value),
        success: () => resolve(),
        fail: () => resolve()
      })
    })
  }
}