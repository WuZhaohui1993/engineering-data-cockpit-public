import request from '@/utils/request'

export function pageDashboardIntegrations(query = {}) {
  return request({ url: '/dashboard/integration/page', method: 'get', params: query })
}

export function pageDashboardIntegrationEndpoints(sourceCode, query = {}) {
  return request({ url: `/dashboard/integration/endpoints/${encodeURIComponent(sourceCode)}/page`, method: 'get', params: query })
}

export function pageDashboardIntegrationBatches(query = {}) {
  return request({ url: '/dashboard/integration/batches/page', method: 'get', params: query })
}

export function pageDashboardIntegrationDeadLetters(query = {}) {
  return request({ url: '/dashboard/integration/dead-letters/page', method: 'get', params: query })
}

export function listDashboardIntegrations(query = {}) {
  return request({ url: '/dashboard/integration/list', method: 'get', params: query })
}

export function getDashboardIntegration(integrationId) {
  return request({ url: `/dashboard/integration/${integrationId}`, method: 'get' })
}

export function addDashboardIntegration(data) {
  return request({ url: '/dashboard/integration', method: 'post', data })
}

export function updateDashboardIntegration(data) {
  return request({ url: '/dashboard/integration', method: 'put', data })
}

export function deleteDashboardIntegration(integrationId) {
  return request({ url: `/dashboard/integration/${integrationId}`, method: 'delete' })
}

export function updateDashboardIntegrationStatus(integrationId, status) {
  return request({ url: `/dashboard/integration/${integrationId}/status`, method: 'post', data: { status } })
}

export function listDashboardIntegrationKeys(integrationId) {
  return request({ url: `/dashboard/integration/${integrationId}/keys`, method: 'get' })
}

export function addDashboardIntegrationKey(integrationId, data) {
  return request({ url: `/dashboard/integration/${integrationId}/keys`, method: 'post', data })
}

export function revokeDashboardIntegrationKey(keyId) {
  return request({ url: `/dashboard/integration/key/${keyId}`, method: 'delete' })
}

export function listDashboardIntegrationBatches(query = {}) {
  return request({ url: '/dashboard/integration/batches', method: 'get', params: query })
}

export function getDashboardIntegrationBatch(batchId) {
  return request({ url: `/dashboard/integration/batch/${batchId}`, method: 'get' })
}

export function listDashboardIntegrationDeadLetters(query = {}) {
  return request({ url: '/dashboard/integration/dead-letters', method: 'get', params: query })
}

export function replayDashboardIntegrationDeadLetter(messageId, reason = '') {
  return request({ url: `/dashboard/integration/dead-letters/${messageId}/replay`, method: 'post', data: { reason } })
}

export function listDashboardIntegrationEndpoints(sourceCode) {
  return request({ url: `/dashboard/integration/endpoints/${encodeURIComponent(sourceCode)}`, method: 'get' })
}

export function addDashboardIntegrationEndpoint(sourceCode, data) {
  return request({ url: `/dashboard/integration/endpoints/${encodeURIComponent(sourceCode)}`, method: 'post', data })
}

export function updateDashboardIntegrationEndpoint(sourceCode, data) {
  return request({ url: `/dashboard/integration/endpoints/${encodeURIComponent(sourceCode)}`, method: 'put', data })
}

export function deleteDashboardIntegrationEndpoint(endpointId) {
  return request({ url: `/dashboard/integration/endpoints/item/${endpointId}`, method: 'delete' })
}

export function testDashboardIntegrationEndpoint(sourceCode, endpointCode, data = {}) {
  return request({ url: `/dashboard/integration/endpoints/${encodeURIComponent(sourceCode)}/${encodeURIComponent(endpointCode)}/test`, method: 'post', data })
}

export function getIntegrationMetrics(params = {}) { return request({ url:'/dashboard/integration/operations/metrics',method:'get',params }); }
export function listIntegrationAudits(params) { return request({ url:'/dashboard/integration/operations/audits',method:'get',params }); }
export function listIntegrationAlerts(params) { return request({ url:'/dashboard/integration/operations/alerts',method:'get',params }); }
export function listIntegrationOperationResources() { return request({ url:'/dashboard/integration/operations/resources',method:'get' }); }
export function getIntegrationWebSocketStatus(params = {}) { return request({ url:'/dashboard/integration/operations/websockets',method:'get',params }); }
export function getIntegrationOperationsPolicy() { return request({ url:'/dashboard/integration/operations/policy',method:'get' }); }
export function updateIntegrationOperationsPolicy(data) { return request({ url:'/dashboard/integration/operations/policy',method:'put',data }); }
export function acknowledgeIntegrationAlert(id) { return request({ url:`/dashboard/integration/operations/alerts/${id}/ack`,method:'post' }); }
export function createInboundDataset(id,data) { return request({ url:`/dashboard/integration/operations/inbound/${id}/dataset`,method:'post',data }); }

export function getIntegrationEndpointConfiguration(id) { return request({ url: `/dashboard/integration/endpoints/item/${id}/configuration`, method: 'get' }); }
export function getIntegrationKeyConfiguration(id) { return request({ url: `/dashboard/integration/key/${id}/configuration`, method: 'get' }); }
