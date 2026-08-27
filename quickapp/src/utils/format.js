const PROVIDER_LABEL = {
  oneapi: '中转',
  openai: 'OpenAI',
  deepseek: 'DeepSeek',
  codex: 'Codex',
  sub2api: 'Sub2API',
  manual: '手动'
}

function fmtNum(v, digits) {
  if (v === null || v === undefined || isNaN(v)) return '--'
  const d = digits === undefined ? 2 : digits
  return Number(v).toFixed(d)
}

function fmtTime(ms) {
  if (!ms) return '--'
  const d = new Date(ms)
  const p = n => (n < 10 ? '0' + n : '' + n)
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export default {
  PROVIDER_LABEL,
  fmtNum,
  fmtTime,
  providerLabel(t) { return PROVIDER_LABEL[t] || t || '--' },
  percent(remaining, total) {
    if (!total || total <= 0) return null
    const p = (remaining / total) * 100
    return Math.max(0, Math.min(100, p))
  },
  remainingText(acc) {
    const unit = acc.unit || ''
    return `${fmtNum(acc.remaining)}${unit}`
  },
  usedText(acc) {
    if (acc.used === null || acc.used === undefined) return ''
    const unit = acc.unit || ''
    return `已用 ${fmtNum(acc.used)}${unit}`
  },
  detailText(acc) {
    if (acc.detail) return acc.detail
    if (acc.group) return acc.group
    return ''
  },
  metaText(acc) {
    const parts = []
    if (acc.total) parts.push(`总额 ${fmtNum(acc.total)}${acc.unit || ''}`)
    if (acc.group) parts.push(acc.group)
    return parts.join(' · ')
  }
}