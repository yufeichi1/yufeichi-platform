export interface Result<T> {
  code: number;
  message: string;
  data: T;
}
export interface PageResult<T> {
  records: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}
export interface UserInfo {
  id: number;
  username: string;
  nickname: string;
  avatar: string | null;
  email: string | null;
  roles: string[];
  permissions: string[];
}
export interface LoginResult {
  token: string;
  tokenType: string;
  expireMinutes: number;
  userInfo: UserInfo;
  permissions: string[];
}
export interface Taxonomy {
  id: number;
  name: string;
  slug: string;
  status: number;
  description?: string;
  sortOrder?: number;
}
export interface TaxonomyInput {
  name: string;
  slug: string;
  status: number;
  description?: string;
  sortOrder?: number;
}
export interface ArticleInput {
  title: string;
  summary: string;
  content: string;
  coverUrl: string | null;
  categoryId: number | null;
  tagIds: number[];
  isTop: number;
  isFeatured: number;
}
export interface ArticleSummary {
  id: number;
  title: string;
  summary: string;
  coverUrl: string | null;
  categoryId: number | null;
  authorId: number;
  status: number;
  isTop: number;
  isFeatured: number;
  viewCount: number;
  publishedAt: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface Article extends ArticleSummary {
  content: string;
  tagIds: number[];
  tags: Taxonomy[];
}
export interface UploadResult {
  id: number;
  originalName: string;
  fileName: string;
  fileUrl: string;
  fileType: string;
  fileExt: string;
  fileSize: number;
  bizType: string;
}
export interface ProjectInput {
  name: string;
  description: string;
  coverUrl: string | null;
  githubUrl: string | null;
  demoUrl: string | null;
  techStack: string;
  sortOrder: number;
  status: number;
}
export interface Project extends ProjectInput {
  id: number;
  createdAt: string;
  updatedAt: string;
}
