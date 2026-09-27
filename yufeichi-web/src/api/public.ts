import { request } from "./http";
import type {
  Article,
  ArticleSummary,
  PageResult,
  Project,
  Taxonomy,
} from "@/types/api";
export const publicApi = {
  articles: (params: {
    pageNum: number;
    pageSize: number;
    categoryId?: number;
    tagId?: number;
  }) =>
    request<PageResult<ArticleSummary>>({ url: "/articles", params }, false),
  article: (id: string) => request<Article>({ url: "/articles/" + id }, false),
  categories: () => request<Taxonomy[]>({ url: "/categories" }, false),
  tags: () => request<Taxonomy[]>({ url: "/tags" }, false),
  projects: (pageNum = 1, pageSize = 9) =>
    request<PageResult<Project>>(
      { url: "/projects", params: { pageNum, pageSize } },
      false,
    ),
  project: (id: string) => request<Project>({ url: "/projects/" + id }, false),
};
