import { request } from "./http";
import type {
  Article,
  ArticleInput,
  ArticleSummary,
  PageResult,
  Taxonomy,
  TaxonomyInput,
  UploadResult,
} from "@/types/api";
export type TaxonomyKind = "categories" | "tags";
export const taxonomyApi = {
  list: (kind: TaxonomyKind) => request<Taxonomy[]>({ url: `/admin/${kind}` }),
  save: (kind: TaxonomyKind, data: TaxonomyInput, id?: number) =>
    request<Taxonomy>({
      url: `/admin/${kind}${id ? `/${id}` : ""}`,
      method: id ? "PUT" : "POST",
      data,
    }),
  remove: (kind: TaxonomyKind, id: number) =>
    request<void>({ url: `/admin/${kind}/${id}`, method: "DELETE" }),
};
export const articleApi = {
  list: (params: {
    pageNum: number;
    pageSize: number;
    keyword?: string;
    status?: number;
  }) => request<PageResult<ArticleSummary>>({ url: "/admin/articles", params }),
  get: (id: number) => request<Article>({ url: `/admin/articles/${id}` }),
  create: (data: ArticleInput) =>
    request<Article>({ url: "/admin/articles", method: "POST", data }),
  update: (id: number, data: ArticleInput) =>
    request<Article>({ url: `/admin/articles/${id}`, method: "PUT", data }),
  publish: (id: number, publish: boolean) =>
    request<Article>({
      url: `/admin/articles/${id}/${publish ? "publish" : "unpublish"}`,
      method: "POST",
    }),
  remove: (id: number) =>
    request<void>({ url: `/admin/articles/${id}`, method: "DELETE" }),
};
export function uploadImage(
  file: File,
  progress: (value: number) => void,
  bizType: "article" | "project" = "article",
) {
  const data = new FormData();
  data.append("file", file);
  data.append("bizType", bizType);
  return request<UploadResult>({
    url: "/admin/files/upload",
    method: "POST",
    data,
    timeout: 60000,
    onUploadProgress: (event) =>
      progress(
        event.total ? Math.round((event.loaded * 100) / event.total) : 0,
      ),
  });
}
