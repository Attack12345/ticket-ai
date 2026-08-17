import request from './request'

// 工单 API（DEV_DOC §6.3）
export const ticketApi = {
  list(params) {
    return request.get('/tickets', { params })
  },
  detail(id) {
    return request.get(`/tickets/${id}`)
  },
  timeline(id) {
    return request.get(`/tickets/${id}/timeline`)
  },
  create(data) {
    return request.post('/tickets', data)
  },
  claim(id) {
    return request.post(`/tickets/${id}/claim`)
  },
  assign(id, agentId) {
    return request.post(`/tickets/${id}/assign`, { agentId })
  },
  reply(id, data) {
    return request.post(`/tickets/${id}/reply`, data)
  },
  resolve(id) {
    return request.post(`/tickets/${id}/resolve`)
  },
  close(id) {
    return request.post(`/tickets/${id}/close`)
  },
  reopen(id) {
    return request.post(`/tickets/${id}/reopen`)
  },
  escalate(id) {
    return request.post(`/tickets/${id}/escalate`)
  },
  cancel(id) {
    return request.post(`/tickets/${id}/cancel`)
  },
  aiSuggest(id) {
    return request.post(`/tickets/${id}/ai-suggest`)
  },
  acceptCategory(id, data) {
    return request.post(`/tickets/${id}/accept-category`, data)
  }
}
