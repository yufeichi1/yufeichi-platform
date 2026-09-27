<template>
  <div class="front-shell">
    <header class="front-header">
      <RouterLink to="/" class="brand"
        >yufeichi<span>随笔与创造</span></RouterLink
      ><RouterLink to="/admin">进入工作台 ↗</RouterLink>
    </header>
    <main><RouterView /></main>
    <footer>YUFEICHI · 把想法写成作品</footer>
  </div>
</template>
