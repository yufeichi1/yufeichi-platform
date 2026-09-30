import axios, { type AxiosRequestConfig } from "axios";
import type { Result } from "@/types/api";

export const TOKEN_KEY = "yufeichi.access-token";
export class ApiError extends Error {
  status: number;
  code: number;
  constructor(message: string, status = 0, code = status) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
let unauthorized: (() => void) | undefined;
export function onUnauthorized(handler: () => void) {
  unauthorized = handler;
}
export function notifyUnauthorized(token: string) {
  if (token === localStorage.getItem(TOKEN_KEY)) unauthorized?.();
}
const client = axios.create({ baseURL: "/api", timeout: 15000 });
export async function request<T>(
  config: AxiosRequestConfig,
  authenticated = true,
): Promise<T> {
  const token = authenticated ? localStorage.getItem(TOKEN_KEY) : null;
  try {
    const response = await client.request<Result<T>>({
      ...config,
      headers: {
        ...config.headers,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
    if (response.data.code !== 0)
      throw new ApiError(
        response.data.message || "操作失败，请重试",
        response.status,
        response.data.code,
      );
    return response.data.data;
  } catch (error: unknown) {
    if (error instanceof ApiError) throw error;
    if (axios.isAxiosError<Result<unknown>>(error)) {
      const status = error.response?.status || 0;
      if (
        status === 401 &&
        authenticated &&
        token &&
        token === localStorage.getItem(TOKEN_KEY)
      )
        notifyUnauthorized(token);
      const fallback =
        status === 403
          ? "你没有执行此操作的权限"
          : status === 401
            ? "登录已失效，请重新登录"
            : error.code === "ECONNABORTED"
              ? "请求超时，请重试"
              : "无法连接服务，请检查网络后重试";
      throw new ApiError(
        error.response?.data?.message || fallback,
        status,
        error.response?.data?.code,
      );
    }
    throw new ApiError("发生未知错误，请重试");
  }
}
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "操作失败，请重试";
}
