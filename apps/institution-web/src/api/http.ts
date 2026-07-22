import axios from "axios";

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "http://127.0.0.1:8080/api",
  timeout: 15000,
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem("jianda_token");
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 || error.response?.data?.code === 401) {
      localStorage.removeItem("jianda_token");
      if (location.pathname !== "/login") location.assign("/login");
    }
    return Promise.reject(error);
  },
);

export function apiMessage(error: unknown): string {
  if (axios.isAxiosError(error))
    return error.response?.data?.message || "服务连接失败，请确认后端已启动";
  return "操作失败，请稍后重试";
}
