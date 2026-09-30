<template>
  <div class="front-shell">
    <header class="front-header">
      <RouterLink to="/" class="brand"
        >yufeichi<span>随笔与创造</span></RouterLink
      >
      <nav class="front-nav" aria-label="主导航">
        <RouterLink to="/" exact-active-class="selected">首页</RouterLink
        ><RouterLink to="/articles" active-class="selected">文章</RouterLink
        ><RouterLink to="/projects" active-class="selected">项目</RouterLink
        ><RouterLink to="/about" active-class="selected">关于</RouterLink
        ><RouterLink to="/admin/ai">AI 助手</RouterLink>
      </nav>
    </header>
    <main id="main-content"><RouterView /></main>
    <footer class="front-footer">
      <span>YUFEICHI · 把想法写成作品</span
      ><RouterLink to="/admin">内容工作台 ↗</RouterLink>
    </footer>
  </div>
</template>
