<script setup lang="ts">
import { computed, ref, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { taxonomyApi, type TaxonomyKind } from "@/api/content";
import { errorMessage } from "@/api/http";
import { useUserStore } from "@/stores/user";
import type { Taxonomy, TaxonomyInput } from "@/types/api";
import PageState from "@/components/PageState.vue";
const props = defineProps<{ kind: TaxonomyKind }>();
const user = useUserStore();
const label = computed(() => (props.kind === "categories" ? "分类" : "标签"));
const permission = computed(() =>
  props.kind === "categories" ? "category" : "tag",
);
const rows = ref<Taxonomy[]>([]),
  loading = ref(false),
  error = ref(""),
  dialog = ref(false),
  saving = ref(false),
  formError = ref("");
const editing = ref<number>(),
  deleting = ref<number>();
const form = ref<TaxonomyInput>({ name: "", slug: "", status: 1 });
async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await taxonomyApi.list(props.kind);
  } catch (e: unknown) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
function open(row?: Taxonomy) {
  editing.value = row?.id;
  form.value = {
    name: row?.name || "",
    slug: row?.slug || "",
    status: row?.status ?? 1,
    ...(props.kind === "categories"
      ? { description: row?.description || "", sortOrder: row?.sortOrder || 0 }
      : {}),
  };
  formError.value = "";
  dialog.value = true;
}
async function save() {
  if (saving.value) return;
  if (
    !form.value.name.trim() ||
    !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(form.value.slug)
  ) {
    formError.value = "请填写名称；别名仅允许小写字母、数字和中间的短横线";
    return;
  }
  saving.value = true;
  formError.value = "";
  try {
    await taxonomyApi.save(
      props.kind,
      { ...form.value, name: form.value.name.trim() },
      editing.value,
    );
    dialog.value = false;
    ElMessage.success("保存成功");
    await load();
  } catch (e: unknown) {
    formError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function remove(row: Taxonomy) {
  if (deleting.value) return;
  deleting.value = row.id;
  try {
    await ElMessageBox.confirm(
      "确认删除“" + row.name + "”？已被文章引用的内容不能删除。",
      "删除" + label.value,
      {
        confirmButtonText: "确认删除",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
  } catch {
    deleting.value = undefined;
    return;
  }
  try {
    await taxonomyApi.remove(props.kind, row.id);
    ElMessage.success("删除成功");
    await load();
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e));
  } finally {
    deleting.value = undefined;
  }
}
onMounted(load);
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="eyebrow">CONTENT ORGANIZATION</p>
        <h1>{{ label }}管理</h1>
        <p class="muted">用清晰的{{ label }}，让内容更有条理。</p>
      </div>
      <el-button
        v-if="user.hasPermission(permission + ':add')"
        type="primary"
        @click="open()"
        >新建{{ label }}</el-button
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
            label="名称"
            min-width="150"
          /><el-table-column
            prop="slug"
            label="别名"
            min-width="150"
          /><el-table-column label="状态" width="110"
            ><template #default="{ row }"
              ><el-tag :type="row.status === 1 ? 'success' : 'info'">{{
                row.status === 1 ? "启用" : "停用"
              }}</el-tag></template
            ></el-table-column
          ><el-table-column
            v-if="kind === 'categories'"
            prop="sortOrder"
            label="排序"
            width="80"
          /><el-table-column label="操作" width="160"
            ><template #default="{ row }"
              ><el-button
                v-if="user.hasPermission(permission + ':update')"
                link
                type="primary"
                @click="open(row)"
                >编辑</el-button
              ><el-button
                v-if="user.hasPermission(permission + ':delete')"
                link
                type="danger"
                :loading="deleting === row.id"
                :disabled="!!deleting"
                @click="remove(row)"
                >删除</el-button
              ></template
            ></el-table-column
          ></el-table
        ></PageState
      >
    </div>
    <el-dialog
      v-model="dialog"
      :title="(editing ? '编辑' : '新建') + label"
      width="min(520px, 92vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      ><form @submit.prevent="save">
        <label for="taxonomy-name">名称</label
        ><el-input
          id="taxonomy-name"
          v-model="form.name"
          maxlength="50"
          :disabled="saving"
        /><label for="taxonomy-slug">别名</label
        ><el-input
          id="taxonomy-slug"
          v-model="form.slug"
          maxlength="80"
          placeholder="例如 development-notes"
          :disabled="saving"
        /><template v-if="kind === 'categories'"
          ><label for="taxonomy-description">描述</label
          ><el-input
            id="taxonomy-description"
            v-model="form.description"
            type="textarea"
            maxlength="255"
            :disabled="saving" /><label>排序</label
          ><el-input-number
            v-model="form.sortOrder"
            :min="0"
            :max="1000000"
            :disabled="saving" /></template
        ><label>状态</label
        ><el-switch
          v-model="form.status"
          :active-value="1"
          :inactive-value="0"
          active-text="启用"
          :disabled="saving"
        />
        <p v-if="formError" role="alert" class="error-text">{{ formError }}</p>
        <div class="dialog-actions">
          <el-button :disabled="saving" @click="dialog = false">取消</el-button
          ><el-button type="primary" native-type="submit" :loading="saving"
            >保存</el-button
          >
        </div>
      </form></el-dialog
    >
  </section>
</template>
