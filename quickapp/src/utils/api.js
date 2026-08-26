import interconn from './interconn'

let seq = 0
const pending = {}
const RPC_TIMEOUT = 30000

const listeners = {}
const eventBus = {
  on(event, handler) {
    ;(listeners[event] = listeners[event] || []).push(handler)
    return () => {
      listeners[event] = (listeners[event] || []).filter(h => h !== handler)
    }
  },
  emit(event, data) {
    ;(listeners[event] || []).forEach(h => {
      try { h(data) } catch (e) { console.warn('event handler error:', event, e) }
    })
  }
}

function call(method, params) {
  const id = ++seq
  const req = { id, method, params: params || {} }

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (pending[id]) {
        delete pending[id]
        reject({ code: -1, msg: 'rpc timeout', method })
      }
    }, RPC_TIMEOUT)

    pending[id] = { resolve, reject, timer }

    interconn.send(req).catch(err => {
      clearTimeout(timer)
      delete pending[id]
      reject(err)
    })
  })
}

interconn.onMessage(payload => {
  if (!payload) return
  if (payload.id !== undefined && payload.__rpc !== true) {
    const p = pending[payload.id]
    if (p) {
      clearTimeout(p.timer)
      delete pending[payload.id]
      if (payload.error) p.reject(payload.error)
      else p.resolve(payload.result)
    }
    return
  }
  if (payload.__event === true) {
    eventBus.emit(payload.event, payload.data)
  }
})

export default {
  listQuota() {
    return call('quota.list')
  },
  refreshQuota() {
    return call('quota.refresh')
  },
  testConn() {
    return call('conn.test')
  },
  getConfig() {
    return call('config.get')
  },
  on(event, handler) { return eventBus.on(event, handler) },
  _emit(event, data) { eventBus.emit(event, data) }
}