<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import PageHeader from "../components/PageHeader.vue";
import { documentApi } from "../api/documents";
import { apiMessage } from "../api/http";
import { Save, CheckCircle2, FileText } from "lucide-vue-next";
const route = useRoute();
const router = useRouter();
const documentId = Number(route.params.id);
const fields = ref<any[]>([]);
const active = ref(0);
const values = ref<string[]>([]);
const confirmed = ref<number[]>([]);
const error = ref("");
const submitting = ref(false);
onMounted(async () => {
  try {
    const response = await documentApi.fields(documentId);
    fields.value = response.data.data.map((field) => ({
      id: field.id,
      label: field.field_label,
      value: field.field_value,
      page: field.page_no,
      quote: field.source_quote,
      confidence: Number(field.confidence),
    }));
    values.value = fields.value.map((field) => field.value);
  } catch (cause) {
    error.value = apiMessage(cause);
  }
});
async function confirm(i: number) {
  try {
    await documentApi.updateField(
      documentId,
      fields.value[i].id,
      values.value[i],
      true,
    );
    if (!confirmed.value.includes(i)) confirmed.value.push(i);
  } catch (cause) {
    error.value = apiMessage(cause);
  }
}
async function finish() {
  submitting.value = true;
  error.value = "";
  try {
    for (let i = 0; i < fields.value.length; i += 1)
      await documentApi.updateField(
        documentId,
        fields.value[i].id,
        values.value[i],
        true,
      );
    await documentApi.review(documentId);
    await router.push(`/documents/${documentId}/publish`);
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    submitting.value = false;
  }
}
</script>
<template>
  <div class="review-page">
    <PageHeader
      title="原文对照审核"
      description="逐项核对 AI 结果与原文依据，确认无误后提交审核。"
      ><button class="btn secondary"><Save :size="17" />保存草稿</button
      ><button
        class="btn primary"
        :disabled="!fields.length || submitting"
        @click="finish"
      >
        {{ submitting ? "正在提交…" : "完成字段审核" }}
      </button></PageHeader
    >
    <div class="review-toolbar">
      <span>老年补贴申请指南</span>
      <div>
        <b>已确认 {{ confirmed.length }} / {{ fields.length }}</b>
        <div class="review-progress">
          <i
            :style="{ width: (confirmed.length / fields.length) * 100 + '%' }"
          ></i>
        </div>
      </div>
    </div>
    <p v-if="error" class="form-error">{{ error }}</p>

    <div v-if="fields.length" class="compare">
      <section class="source-pane">
        <div class="pane-title">
          <FileText :size="18" /><b>原始材料</b
          ><span>第 {{ fields[active].page }} 页 / 共 3 页</span>
        </div>
        <article class="paper">
          <h2>浦江街道老年补贴办理通知</h2>
          <p>
            为进一步做好本街道老年人关爱服务工作，根据本市有关规定，现将高龄老年人生活补贴申请事项通知如下。
          </p>
          <h3>一、申请对象</h3>
          <p :class="{ highlight: active === 0 }">
            补贴对象为具有本市户籍且年满八十周岁的老年人。已享受同类补贴待遇的，不重复发放。
          </p>
          <h3>二、申请材料</h3>
          <p :class="{ highlight: active === 2 }">
            申请材料：身份证及户口簿原件、本人银行卡复印件、近期一寸免冠照片一张。
          </p>
          <h3>三、办理方式</h3>
          <p :class="{ highlight: active === 3 }">
            请申请人至户籍所在地社区服务窗口提出申请。工作人员受理后，在十个工作日内完成审核。
          </p>
          <p :class="{ highlight: active === 4 }">
            咨询电话：021-12345，工作日 9:00—17:00。
          </p>
        </article>
      </section>
      <section class="ai-pane">
        <div class="pane-title">
          <b>AI 结构化结果</b><span>请逐项确认</span>
        </div>
        <div class="review-fields">
          <article
            v-for="(f, i) in fields"
            :key="f.id"
            :class="{ active: active === i, confirmed: confirmed.includes(i) }"
            @click="active = i"
          >
            <header>
              <b>{{ f.label }}</b
              ><span v-if="confirmed.includes(i)"><CheckCircle2 />已确认</span
              ><span v-else :class="{ risk: f.confidence < 0.93 }">{{
                f.confidence < 0.93 ? "请重点核对" : "待确认"
              }}</span>
            </header>
            <textarea v-model="values[i]" rows="2"></textarea>
            <div class="trace">
              <span>原文依据 · 第 {{ f.page }} 页</span>
              <p>“{{ f.quote }}”</p>
            </div>
            <button class="confirm-btn" @click.stop="confirm(i)">
              <CheckCircle2 :size="17" />确认此字段
            </button>
          </article>
        </div>
      </section>
    </div>
  </div>
</template>
