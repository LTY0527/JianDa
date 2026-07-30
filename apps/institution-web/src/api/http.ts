import axios from "axios";

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
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
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    const serverMessage = error.response?.data?.message;
    const requestId = error.response?.headers?.["x-request-id"];
    let message = serverMessage || "操作失败，请稍后重试";
    if (!error.response) message = "服务暂时不可达，请检查网络后重试";
    else if (status === 401 && location.pathname === "/login") {
      message = serverMessage || "账号或密码错误";
    } else if (status === 401) message = "登录状态已失效，请重新登录";
    else if (status === 403) message = serverMessage || "当前机构无权执行此操作";
    else if (status === 404) message = serverMessage || "请求的记录不存在";
    else if (status === 400) message = serverMessage || "材料信息不完整，请检查后重试";
    else if (status === 413) message = "文件超过 20MB，请选择较小的文件";
    else if (status === 500) message = "服务器处理失败，请稍后重试";
    else if (status === 502 || status === 504) {
      message = serverMessage || "网关暂时无法连接服务，请稍后重试";
    }
    else if (status === 503) message = serverMessage || "AI 服务调用失败，请稍后重试";
    return requestId ? `${message}（请求编号：${requestId}）` : message;
  }
  return "操作失败，请稍后重试";
}
