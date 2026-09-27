import { request } from "./http";
import type { Project, ProjectInput, PageResult } from "@/types/api";
export const projectApi = {
  list: (pageNum: number) =>
    request<PageResult<Project>>({
      url: "/admin/projects",
      params: { pageNum, pageSize: 10 },
    }),
  save: (data: ProjectInput, id?: number) =>
    request<Project>({
      url: "/admin/projects" + (id ? "/" + id : ""),
      method: id ? "PUT" : "POST",
      data,
    }),
  status: (id: number, status: number) =>
    request<Project>({
      url: "/admin/projects/" + id + "/status",
      method: "PUT",
      data: { status },
    }),
  remove: (id: number) =>
    request<void>({ url: "/admin/projects/" + id, method: "DELETE" }),
};
