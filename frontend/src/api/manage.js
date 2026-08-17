import request from './request'

// 坐席 / 技能组 / SLA（DEV_DOC §6.5）
export const agentApi = {
  list(params) {
    return request.get('/agents', { params })
  },
  updateStatus(id, status) {
    return request.put(`/agents/${id}/status`, { status })
  },
  updateSkillTags(id, skillTags) {
    return request.put(`/agents/${id}`, { skillTags })
  }
}

export const skillGroupApi = {
  list() {
    return request.get('/skill-groups')
  },
  create(data) {
    return request.post('/skill-groups', data)
  },
  update(id, data) {
    return request.put(`/skill-groups/${id}`, data)
  },
  remove(id) {
    return request.delete(`/skill-groups/${id}`)
  },
  setAgents(id, agentIds) {
    return request.put(`/skill-groups/${id}/agents`, { agentIds })
  }
}

export const slaApi = {
  list() {
    return request.get('/sla-policies')
  },
  create(data) {
    return request.post('/sla-policies', data)
  },
  update(id, data) {
    return request.put(`/sla-policies/${id}`, data)
  },
  remove(id) {
    return request.delete(`/sla-policies/${id}`)
  }
}
