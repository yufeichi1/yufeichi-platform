<template>
  <section class="public-section about-page">
    <p class="eyebrow">ABOUT / YUFEICHI</p>
    <h1>你好，我是 Yufeichi。</h1>
    <p class="about-lead">
      这里是我的个人创作空间，<br />也是把想法付诸实践的记录。
    </p>
    <div class="about-grid">
      <div>
        <h2>记录与分享</h2>
        <p>
          通过文章记录开发过程中的思考，通过项目展示实际完成的作品。内容会随着实践逐步积累。
        </p>
        <RouterLink to="/articles" class="read-link">阅读文章 →</RouterLink>
      </div>
      <div>
        <h2>正在构建</h2>
        <p>
          Yufeichi Platform 是我的个人全栈项目，使用 Spring Boot 3、Java 21 和
          Vue 3 构建，连接内容创作与公开展示。
        </p>
        <RouterLink to="/projects" class="read-link">浏览项目 →</RouterLink>
      </div>
    </div>
    <a
      class="primary-link"
      href="https://github.com/yufeichi1/yufeichi-platform"
      target="_blank"
      rel="noopener noreferrer"
      >项目源码 ↗</a
    >
  </section>
</template>
