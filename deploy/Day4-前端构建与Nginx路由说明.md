# Day4 前端构建与 Nginx 路由约定

本文件是部署说明，不代表服务器已经变更。Day6 仍需备份、实际 Nginx 检查、HTTPS、端口与持久化配置。

## 本地构建与生产产物预览

在 yufeichi-web 中运行 pnpm install --frozen-lockfile、pnpm build，产物为 dist/。pnpm preview --host 127.0.0.1 仅用于检查构建产物，不用于生产托管。

开发和预览的 /api、/uploads 代理目标都来自 API_PROXY_TARGET，默认 http://127.0.0.1:8080。变量只在 Vite 进程中读取，不带 VITE_ 前缀，不向浏览器暴露数据库等秘密。

运行隔离浏览器测试：
~~~powershell
node scripts/test-web.mjs
# 验证 dist 产物及深层路由（先 pnpm --dir yufeichi-web build）
$env:E2E_PREVIEW = '1'
node scripts/test-web.mjs
Remove-Item Env:E2E_PREVIEW
~~~

## Nginx 路由要求

以下片段需合并到已备份并审核的站点配置中；不是完整的 HTTPS 配置。目录与用户必须按服务器真实状态调整。

~~~nginx
root /var/www/yufeichi;
index index.html;

# 没有结尾 /：保留原始 /api 前缀。
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 6m;
}

# 可由后端读取配置的持久上传目录，也可以配置独立的 alias。
# 这里复用后端映射，必须避免落入 SPA fallback。
location /uploads/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
}

# 构建文件缺失必须 404，不能返回 index.html 冒充 JS。
location /assets/ {
    try_files $uri =404;
}

# 支持 /articles/123、/projects/123、/admin/articles/123/edit 刷新。
location / {
    try_files $uri $uri/ /index.html;
}
~~~

部署后必须实际验证：

1. 深层地址直接打开、刷新返回前端入口，再由对应 API 获取内容。
2. /api/articles/... 的 404 保持 JSON/404，不能变成 index.html/200。
3. /uploads/... 返回图片；不存在图片返回 404，不返回 SPA 入口。
4. SPA 未知路由由页面显示 404 状态。由于是客户端渲染，HTML 文档本身可能是 200；内容 API 的不存在/未公开响应仍为真实 404。SEO HTTP 404 或 SSR 不在本轮范围。
5. 后端只监听受控地址，生产来源与 TLS 设置遵循 Day6 的正式配置，不直接套用开发环境。

## 内容地址策略

Markdown 关闭原生 HTML，生成的 HTML 通过 DOMPurify 标签/属性白名单净化后才渲染；不在净化后再交给其他 HTML 插件处理。外链仅接受 HTTP/HTTPS 且不含嵌入式账号密码，带 noopener noreferrer。站内链接仅允许公开文章/项目/关于地址。

封面和 Markdown 图片只允许 /uploads/{avatar|article|project|other}/{规范文件名}.png 或 .jpg。远程图片、data:、协议相对地址、路径穿越、SVG 都不会加载；已有远程封面会显示占位图。上传 WebP 由后端规范化为 PNG，仍可正常展示。

实现参考：[markdown-it 官方文档](https://markdown-it.github.io/markdown-it/)、[DOMPurify 官方说明](https://github.com/cure53/DOMPurify)。渲染边界有浏览器攻击样例测试；后续仍应维护依赖安全更新。
