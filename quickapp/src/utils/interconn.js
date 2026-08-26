import interconnect from '@system.interconnect'

let conn = null
let readyState = 2 // 1=connected, 2=disconnected
const messageHandlers = []
const stateHandlers = []

function init() {
  if (conn) return conn
  conn = interconnect.instance()

  conn.onopen = (data) => {
    readyState = 1
    stateHandlers.forEach(h => {
      try { h(1, data && data.isReconnected) } catch (e) { console.warn(e) }
    })
  }
  conn.onclose = (data) => {
    readyState = 2
    stateHandlers.forEach(h => {
      try { h(2, data) } catch (e) { console.warn(e) }
    })
  }
  conn.onerror = (data) => {
    readyState = 2
    stateHandlers.forEach(h => {
      try { h(2, data) } catch (e) { console.warn(e) }
    })
  }
  conn.onmessage = (data) => {
    let payload
    try {
      payload = (typeof data.data === 'string') ? JSON.parse(data.data) : data.data
    } catch (e) {
      console.warn('[interconn] parse message failed:', e, data && data.data)
      return
    }
    messageHandlers.forEach(h => {
      try { h(payload) } catch (e) {
        console.error('[interconn] message handler error:', e)
      }
    })
  }
  return conn
}

export default {
  init,
  send(data) {
    if (!conn || readyState !== 1) {
      return Promise.reject({ code: 1006, msg: 'not connected' })
    }
    return new Promise((resolve, reject) => {
      conn.send({
        data: { __rpc: true, ...data },
        success: resolve,
        fail: (err, code) => reject({ code, msg: err && err.data })
      })
    })
  },
  onMessage(handler) {
    messageHandlers.push(handler)
    return () => {
      const i = messageHandlers.indexOf(handler)
      if (i >= 0) messageHandlers.splice(i, 1)
    }
  },
  onState(handler) {
    stateHandlers.push(handler)
    return () => {
      const i = stateHandlers.indexOf(handler)
      if (i >= 0) stateHandlers.splice(i, 1)
    }
  },
  getReadyState() { return readyState },
  diagnose(timeout = 10000) {
    return new Promise((resolve, reject) => {
      if (!conn) return reject({ code: 1006, msg: 'conn not init' })
      conn.diagnosis({ timeout, success: resolve, fail: (e, c) => reject({ code: c, msg: e }) })
    })
  }
}