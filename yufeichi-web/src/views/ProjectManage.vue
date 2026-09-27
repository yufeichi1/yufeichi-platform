<script setup lang="ts">
import { ref, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { projectApi } from "@/api/projects";
import { errorMessage } from "@/api/http";
import { externalUrl } from "@/utils/urls";
import { useUserStore } from "@/stores/user";
import type { Project, ProjectInput } from "@/types/api";
import UploadImage from "@/components/UploadImage.vue";
import PageState from "@/components/PageState.vue";
const user = useUserStore(),
  rows = ref<Project[]>([]),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref(""),
  dialog = ref(false),
  saving = ref(false),
  uploading = ref(false),
  formError = ref(""),
  editing = ref<number>(),
  acting = ref<number>();
const blank = (): ProjectInput => ({
  name: "",
  description: "",
  coverUrl: null,
  githubUrl: null,
  demoUrl: null,
  techStack: "",
  sortOrder: 0,
  status: 0,
});
const form = ref<ProjectInput>(blank());
let generation = 0;
async function load() {
  const run = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const result = await projectApi.list(page.value);
    if (run === generation) {
      rows.value = result.records;
      total.value = result.total;
    }
  } catch (e: unknown) {
    if (run === generation) error.value = errorMessage(e);
  } finally {
    if (run === generation) loading.value = false;
  }
}
function open(row?: Project) {
  editing.value = row?.id;
  form.value = row
    ? {
        name: row.name,
        description: row.description,
        coverUrl: row.coverUrl,
        githubUrl: row.githubUrl,
        demoUrl: row.demoUrl,
        techStack: row.techStack || "",
        sortOrder: row.sortOrder,
        status: row.status,
      }
    : blank();
  formError.value = "";
  dialog.value = true;
}
async function save() {
  if (saving.value || uploading.value) return;
  if (!form.value.name.trim() || !form.value.description.trim()) {
    formError.value = "请填写项目名称和描述";
    return;
  }
  if (
    [form.value.githubUrl, form.value.demoUrl].some(
      (value) => value && !externalUrl(value),
    )
  ) {
    formError.value =
      "项目外链必须是有效的 HTTP 或 HTTPS 地址，不能含账号或密码";
    return;
  }
  saving.value = true;
  formError.value = "";
  try {
    await projectApi.save(
      {
        ...form.value,
        name: form.value.name.trim(),
        githubUrl: form.value.githubUrl || null,
        demoUrl: form.value.demoUrl || null,
      },
      editing.value,
    );
    dialog.value = false;
    ElMessage.success("项目保存成功");
    await load();
  } catch (e: unknown) {
    formError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function act(row: Project, remove = false) {
  if (acting.value) return;
  acting.value = row.id;
  const verb = remove ? "删除" : row.status === 1 ? "隐藏" : "展示";
  try {
    await ElMessageBox.confirm(
      "确认" + verb + "“" + row.name + "”？",
      verb + "项目",
      {
        confirmButtonText: "确认" + verb,
        cancelButtonText: "取消",
        type: remove ? "warning" : "info",
      },
    );
  } catch {
    acting.value = undefined;
    return;
  }
  try {
    if (remove) await projectApi.remove(row.id);
    else await projectApi.status(row.id, row.status === 1 ? 0 : 1);
    if (remove && rows.value.length === 1 && page.value > 1) page.value--;
    await load();
    ElMessage.success(verb + "成功");
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
        <p class="eyebrow">IDEAS INTO PRACTICE</p>
        <h1>
          项目管理 <span class="count">{{ total }}</span>
        </h1>
        <p class="muted">整理作品，让实践留下记录。排序值越小越靠前。</p>
      </div>
      <el-button
        v-if="user.hasPermission('project:add')"
        type="primary"
        @click="open()"
        >新建项目</el-button
      >
    </div>
    <div class="panel">
      <PageState
        :loading="loading"
        :error="error"
        :empty="!rows.length"
        @retry="load"
        ><el-table :data="rows"
          ><el-table-column
            prop="name"
            label="项目名称"
            min-width="180"
          /><el-table-column
            prop="techStack"
            label="技术栈"
            min-width="150"
          /><el-table-column
            prop="sortOrder"
            label="排序"
            width="80"
          /><el-table-column label="状态" width="100"
            ><template #default="{ row }">{{
              row.status === 1 ? "展示中" : "已隐藏"
            }}</template></el-table-column
          ><el-table-column label="操作" width="220"
            ><template #default="{ row }"
              ><el-button
                v-if="user.hasPermission('project:update')"
                link
                type="primary"
                @click="open(row)"
                >编辑</el-button
              ><el-button
                v-if="user.hasPermission('project:update')"
                link
                :disabled="!!acting"
                @click="act(row)"
                >{{ row.status === 1 ? "隐藏" : "展示" }}</el-button
              ><el-button
                v-if="user.hasPermission('project:delete')"
                link
                type="danger"
                :disabled="!!acting"
                @click="act(row, true)"
                >删除</el-button
              ></template
            ></el-table-column
          ></el-table
        ></PageState
      >
      <div v-if="total" class="pagination">
        <span>共 {{ total }} 个项目</span
        ><el-pagination
          v-model:current-page="page"
          :page-size="10"
          :total="total"
          layout="prev,pager,next"
          @current-change="load"
        />
      </div>
    </div>
    <el-dialog
      v-model="dialog"
      :title="editing ? '编辑项目' : '新建项目'"
      width="min(720px,94vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving && !uploading"
      :show-close="!saving && !uploading"
      ><form @submit.prevent="save">
        <label for="project-name">项目名称</label
        ><el-input
          id="project-name"
          v-model="form.name"
          maxlength="100"
          :disabled="saving"
        /><label for="project-description">项目描述</label
        ><el-input
          id="project-description"
          v-model="form.description"
          type="textarea"
          :rows="6"
          maxlength="20000"
          placeholder="支持 Markdown"
          :disabled="saving"
        /><label for="project-tech">技术栈</label
        ><el-input
          id="project-tech"
          v-model="form.techStack"
          maxlength="500"
          :disabled="saving"
        /><label for="project-github">GitHub 地址</label
        ><el-input
          id="project-github"
          v-model="form.githubUrl"
          maxlength="500"
          placeholder="https://github.com/..."
          :disabled="saving"
        /><label for="project-demo">Demo 地址</label
        ><el-input
          id="project-demo"
          v-model="form.demoUrl"
          maxlength="500"
          placeholder="https://..."
          :disabled="saving"
        /><label>封面</label
        ><UploadImage
          v-model="form.coverUrl"
          biz-type="project"
          :disabled="saving || !user.hasPermission('file:upload')"
          @busy="uploading = $event"
        /><label>排序（越小越靠前）</label
        ><el-input-number
          v-model="form.sortOrder"
          aria-label="项目排序"
          :min="0"
          :max="1000000"
          :disabled="saving"
        /><label>公开状态</label
        ><el-switch
          v-model="form.status"
          :active-value="1"
          :inactive-value="0"
          active-text="展示"
          inactive-text="隐藏"
          :disabled="saving"
        />
        <p v-if="formError" role="alert" class="error-text">{{ formError }}</p>
        <div class="dialog-actions">
          <el-button :disabled="saving || uploading" @click="dialog = false"
            >取消</el-button
          ><el-button
            type="primary"
            native-type="submit"
            :loading="saving"
            :disabled="uploading"
            >保存项目</el-button
          >
        </div>
      </form></el-dialog
    >
  </section>
</template>
