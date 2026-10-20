// W4-1 会话续接:conversation_id 只存 sessionStorage(关标签页即失效),键带用户名,
// 同一浏览器登录不同用户不共用存储键;登出/401 时由 clearAuth 同步清理。
// 存储不可用(隐私模式等)时全部静默降级:仅失去续会话能力,不影响对话本身。

const PREFIX = 'sca_conv:'

function key(username) {
  return `${PREFIX}${username || ''}`
}

export function readConversationId(username) {
  try {
    return sessionStorage.getItem(key(username)) || null
  } catch {
    return null
  }
}

export function saveConversationId(username, conversationId) {
  try {
    sessionStorage.setItem(key(username), conversationId)
  } catch {
    // 忽略:降级为不续会话
  }
}

export function clearConversationId(username) {
  try {
    sessionStorage.removeItem(key(username))
  } catch {
    // 忽略:降级为不续会话
  }
}
