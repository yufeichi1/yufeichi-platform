<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { articleApi, taxonomyApi } from "@/api/content";
import { errorMessage } from "@/api/http";
import { useUserStore } from "@/stores/user";
import { preserveDraft, takeDraft } from "@/stores/editorDraft";
import type { ArticleInput, Taxonomy } from "@/types/api";
import PageState from "@/components/PageState.vue";
import UploadImage from "@/components/UploadImage.vue";
const route = useRoute(),
  router = useRouter(),
  user = useUserStore();
const id = ref(route.params.id ? Number(route.params.id) : undefined);
const draftKey = `${user.user?.id}:${route.path}`;
const form = ref<ArticleInput>({
  title: "",
  summary: "",
  content: "",
  coverUrl: null,
  categoryId: null,
  tagIds: [],
  isTop: 0,
  isFeatured: 0,
});
const categories = ref<Taxonomy[]>([]),
  tags = ref<Taxonomy[]>([]);
const loading = ref(true),
  error = ref(""),
  saveError = ref(""),
  busy = ref(false),
  uploading = ref(false),
  preview = ref(false),
  status = ref(0);
const baseline = ref(""),
  bypassLeave = ref(false);
const canSave = computed(() =>
  user.hasPermission(id.value ? "article:update" : "article:add"),
);
const dirty = computed(
  () => baseline.value !== "" && baseline.value !== JSON.stringify(form.value),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [categoryRows, tagRows, article] = await Promise.all([
      user.hasPermission("category:list")
        ? taxonomyApi.list("categories")
        : Promise.resolve([]),
      user.hasPermission("tag:list")
        ? taxonomyApi.list("tags")
        : Promise.resolve([]),
      id.value ? articleApi.get(id.value) : Promise.resolve(null),
    ]);
    categories.value = categoryRows;
    tags.value = tagRows;
    if (article) {
      form.value = {
        title: article.title,
        summary: article.summary || "",
        content: article.content,
        coverUrl: article.coverUrl,
        categoryId: article.categoryId,
        tagIds: article.tagIds,
        isTop: article.isTop,
        isFeatured: article.isFeatured,
      };
      status.value = article.status;
      if (
        article.categoryId &&
        !categories.value.some((c) => c.id === article.categoryId)
      )
        categories.value.push({
          id: article.categoryId,
          name: "当前分类 #" + article.categoryId,
          slug: "",
          status: 0,
        });
      for (const tag of article.tags)
        if (!tags.value.some((t) => t.id === tag.id)) tags.value.push(tag);
    }
    baseline.value = JSON.stringify(form.value);
    const interruptedDraft = takeDraft(draftKey);
    if (interruptedDraft) {
      form.value = interruptedDraft;
      ElMessage.info("已恢复登录过期前尚未保存的内容");
    }
  } catch (e: unknown) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
async function save(publish = false) {
  if (busy.value || uploading.value || !canSave.value) return;
  if (!form.value.title.trim() || !form.value.content.trim()) {
    saveError.value = "请填写文章标题和正文";
    return;
  }
  busy.value = true;
  if (publish) {
    try {
      await ElMessageBox.confirm(
        "保存当前内容并公开发布这篇文章？",
        "发布文章",
        { confirmButtonText: "确认发布", cancelButtonText: "取消" },
      );
    } catch {
      busy.value = false;
      return;
    }
  }
  saveError.value = "";
  try {
    const data = {
      ...form.value,
      title: form.value.title.trim(),
      categoryId: form.value.categoryId || null,
    };
    const saved = id.value
      ? await articleApi.update(id.value, data)
      : await articleApi.create(data);
    id.value = saved.id;
    status.value = saved.status;
    baseline.value = JSON.stringify(form.value);
    if (publish) {
      await articleApi.publish(saved.id, true);
      status.value = 1;
    }
    ElMessage.success(publish ? "发布成功" : "保存成功");
    if (!route.params.id) {
      bypassLeave.value = true;
      await router.replace("/admin/articles/" + saved.id + "/edit");
    }
  } catch (e: unknown) {
    saveError.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
onBeforeRouteLeave(async (to) => {
  if (to.path === "/login" && to.query.expired && dirty.value)
    preserveDraft(draftKey, form.value);
  if (to.path === "/login" || bypassLeave.value || !dirty.value) return true;
  try {
    await ElMessageBox.confirm("内容尚未保存，确认离开？", "离开编辑页", {
      confirmButtonText: "离开",
      cancelButtonText: "继续编辑",
      type: "warning",
    });
    return true;
  } catch {
    return false;
  }
});
onMounted(load);
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <RouterLink class="back-link" to="/admin/articles"
          >← 文章管理</RouterLink
        >
        <h1>{{ id ? "编辑文章" : "新建文章" }}</h1>
        <p class="muted">
          {{
            status === 1
              ? "已发布 · 保存修改会立即更新公开内容"
              : status === 2
                ? "已下架 · 可以继续修改并重新发布"
                : "草稿 · 慢慢打磨，再与世界分享"
          }}
        </p>
      </div>
      <div class="editor-actions">
        <el-button
          v-if="canSave"
          :loading="busy"
          :disabled="uploading || loading || !!error"
          @click="save(false)"
          >{{ status === 1 ? "保存修改" : "保存草稿" }}</el-button
        ><el-button
          v-if="canSave && user.hasPermission('article:publish')"
          type="primary"
          :loading="busy"
          :disabled="uploading || loading || !!error"
          @click="save(true)"
          >发布文章</el-button
        >
      </div>
    </div>
    <PageState :loading="loading" :error="error" @retry="load"
      ><el-alert
        v-if="saveError"
        :title="saveError"
        type="error"
        :closable="false"
        class="save-error"
      />
      <div class="editor-grid">
        <div class="panel editor-content">
          <label for="article-title"
            >文章标题 <span class="required">*</span></label
          ><el-input
            id="article-title"
            v-model="form.title"
            maxlength="200"
            show-word-limit
            placeholder="给你的想法一个标题"
            :disabled="busy || !canSave"
          /><label for="article-summary">摘要</label
          ><el-input
            id="article-summary"
            v-model="form.summary"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="用几句话介绍这篇文章…"
            :disabled="busy || !canSave"
          />
          <div class="content-label">
            <label for="article-content"
              >正文 <span class="required">*</span></label
            ><el-switch v-model="preview" active-text="纯文本预览" />
          </div>
          <pre v-if="preview" class="text-preview">{{
            form.content || "正文预览将在这里显示。"
          }}</pre>
          <el-input
            v-else
            id="article-content"
            v-model="form.content"
            type="textarea"
            :rows="20"
            maxlength="200000"
            placeholder="开始写作，支持保存 Markdown 文本…"
            :disabled="busy || !canSave"
          />
          <p class="help muted">
            {{ form.content.length }} 字符 ·
            {{ dirty ? "有未保存的修改" : "内容已同步" }}
          </p>
        </div>
        <aside class="editor-meta">
          <div class="panel">
            <h3>封面图片</h3>
            <UploadImage
              v-model="form.coverUrl"
              :disabled="busy || !canSave || !user.hasPermission('file:upload')"
              @busy="uploading = $event"
            />
          </div>
          <div class="panel">
            <h3>文章设置</h3>
            <label for="article-category">分类</label
            ><el-select
              id="article-category"
              v-model="form.categoryId"
              placeholder="选择分类"
              clearable
              :disabled="
                busy || !canSave || !user.hasPermission('category:list')
              "
              ><el-option
                v-for="item in categories"
                :key="item.id"
                :label="item.name"
                :value="item.id"
                :disabled="
                  item.status !== 1 && item.id !== form.categoryId
                " /></el-select
            ><label for="article-tags">标签</label
            ><el-select
              id="article-tags"
              v-model="form.tagIds"
              multiple
              filterable
              :multiple-limit="50"
              placeholder="选择标签"
              :disabled="busy || !canSave || !user.hasPermission('tag:list')"
              ><el-option
                v-for="item in tags"
                :key="item.id"
                :label="item.name"
                :value="item.id"
                :disabled="item.status !== 1 && !form.tagIds.includes(item.id)"
            /></el-select>
            <div class="editor-flags">
              <el-checkbox
                v-model="form.isTop"
                :true-value="1"
                :false-value="0"
                :disabled="busy || !canSave"
                >置顶</el-checkbox
              ><el-checkbox
                v-model="form.isFeatured"
                :true-value="1"
                :false-value="0"
                :disabled="busy || !canSave"
                >精选</el-checkbox
              >
            </div>
          </div>
        </aside>
      </div></PageState
    >
  </section>
</template>
