<script setup lang="ts">
import { ref, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { articleApi } from "@/api/content";
import { errorMessage } from "@/api/http";
import { useUserStore } from "@/stores/user";
import type { ArticleSummary } from "@/types/api";
import PageState from "@/components/PageState.vue";
const user = useUserStore();
const rows = ref<ArticleSummary[]>([]),
  total = ref(0),
  page = ref(1),
  status = ref<number | undefined>(),
  keyword = ref("");
const loading = ref(false),
  error = ref(""),
  acting = ref<number>();
let generation = 0;
const statusNames: Record<number, string> = {
  0: "草稿",
  1: "已发布",
  2: "已下架",
};
async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const result = await articleApi.list({
      pageNum: page.value,
      pageSize: 10,
      status: status.value,
      keyword: keyword.value.trim() || undefined,
    });
    if (current === generation) {
      rows.value = result.records;
      total.value = result.total;
    }
  } catch (e: unknown) {
    if (current === generation) error.value = errorMessage(e);
  } finally {
    if (current === generation) loading.value = false;
  }
}
function filter() {
  page.value = 1;
  void load();
}
async function act(
  row: ArticleSummary,
  action: "publish" | "unpublish" | "delete",
) {
  if (acting.value) return;
  acting.value = row.id;
  const verb = { publish: "发布", unpublish: "下架", delete: "删除" }[action];
  try {
    await ElMessageBox.confirm(
      "确认" + verb + "“" + row.title + "”？",
      verb + "文章",
      {
        confirmButtonText: "确认" + verb,
        cancelButtonText: "取消",
        type: action === "delete" ? "warning" : "info",
      },
    );
  } catch {
    acting.value = undefined;
    return;
  }
  try {
    if (action === "delete") await articleApi.remove(row.id);
    else await articleApi.publish(row.id, action === "publish");
    ElMessage.success(verb + "成功");
    if (action === "delete" && rows.value.length === 1 && page.value > 1)
      page.value--;
    await load();
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e));
  } finally {
    acting.value = undefined;
  }
}
onMounted(load);
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="eyebrow">YOUR WRITING, IN ONE PLACE</p>
        <h1>
          文章管理 <span class="count">{{ total }}</span>
        </h1>
        <p class="muted">整理草稿，打磨想法，发布值得分享的内容。</p>
      </div>
      <RouterLink
        v-if="user.hasPermission('article:add')"
        to="/admin/articles/new"
        ><el-button type="primary">＋ 新建文章</el-button></RouterLink
      >
    </div>
    <div class="panel">
      <div class="list-toolbar">
        <el-radio-group v-model="status" @change="filter"
          ><el-radio-button :value="undefined">全部</el-radio-button
          ><el-radio-button :value="0">草稿</el-radio-button
          ><el-radio-button :value="1">已发布</el-radio-button
          ><el-radio-button :value="2">已下架</el-radio-button></el-radio-group
        >
        <form class="search-form" @submit.prevent="filter">
          <el-input
            v-model="keyword"
            aria-label="搜索文章"
            placeholder="搜索文章标题…"
            clearable
            maxlength="100"
          /><el-button native-type="submit" :loading="loading">搜索</el-button>
        </form>
      </div>
      <PageState
        :loading="loading"
        :error="error"
        :empty="!rows.length"
        @retry="load"
        ><el-table :data="rows"
          ><el-table-column label="文章" min-width="240"
            ><template #default="{ row }"
              ><RouterLink
                class="article-title"
                :to="'/admin/articles/' + row.id + '/edit'"
                >{{ row.title }}</RouterLink
              >
              <p class="table-summary">
                {{ row.summary || "暂无摘要" }}
              </p></template
            ></el-table-column
          ><el-table-column label="状态" width="100"
            ><template #default="{ row }"
              ><el-tag :type="row.status === 1 ? 'success' : 'info'">{{
                statusNames[row.status]
              }}</el-tag></template
            ></el-table-column
          ><el-table-column label="最后更新" min-width="165"
            ><template #default="{ row }">{{
              row.updatedAt?.replace("T", " ").slice(0, 16)
            }}</template></el-table-column
          ><el-table-column label="操作" width="230"
            ><template #default="{ row }"
              ><RouterLink :to="'/admin/articles/' + row.id + '/edit'">{{
                user.hasPermission("article:update") ? "编辑" : "查看"
              }}</RouterLink
              ><el-button
                v-if="user.hasPermission('article:publish')"
                link
                type="primary"
                :disabled="!!acting"
                :loading="acting === row.id"
                @click="act(row, row.status === 1 ? 'unpublish' : 'publish')"
                >{{ row.status === 1 ? "下架" : "发布" }}</el-button
              ><el-button
                v-if="user.hasPermission('article:delete')"
                link
                type="danger"
                :disabled="!!acting"
                @click="act(row, 'delete')"
                >删除</el-button
              ></template
            ></el-table-column
          ></el-table
        ></PageState
      >
      <div v-if="total" class="pagination">
        <span class="muted">共 {{ total }} 篇文章</span
        ><el-pagination
          v-model:current-page="page"
          :page-size="10"
          :total="total"
          layout="prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>
  </section>
</template>
