<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import PageHeader from "../components/PageHeader.vue";
import { documentApi } from "../api/documents";
import { apiMessage } from "../api/http";
import {
  CircleCheck,
  LoaderCircle,
  WandSparkles,
  ListChecks,
  ArrowRight,
} from "lucide-vue-next";
const route = useRoute();
const documentId = Number(route.params.id);
const fields = ref<any[]>([]);
const steps = ref<any[][]>([]);
const error = ref("");
onMounted(async () => {
  try {
    const [fieldResponse, generatedResponse] = await Promise.all([
      documentApi.fields(documentId),
      documentApi.generated(documentId),
    ]);
    fields.value = fieldResponse.data.data.map((field) => ({
      id: field.id,
      label: field.field_label,
      value: field.field_value,
      page: field.page_no,
      quote: field.source_quote,
      confidence: Number(field.confidence),
    }));
    const stepContent = generatedResponse.data.data.find(
      (item) => item.content_type === "STEP_CARDS",
    )?.content_json;
    const parsed =
      typeof stepContent === "string" ? JSON.parse(stepContent) : [];
    steps.value = parsed.map((step: any) => [step.title, step.description]);
  } catch (cause) {
    error.value = apiMessage(cause);
  }
});
</script>
<template>
  <div>
    <PageHeader title="AI 处理结果" description="查看结构化字段、通俗版摘要和办理步骤。" :breadcrumbs="['材料管理', '处理结果']" status="待审核"
      ><RouterLink class="btn primary" :to="`/documents/${documentId}/review`"
        >进入对照审核<ArrowRight :size="17" /></RouterLink
    ></PageHeader>
    <section class="process-rail">
      <div class="done">
        <CircleCheck /><span><b>材料上传</b><small>原始文件已保存</small></span>
      </div>
      <i></i>
      <div class="done">
        <CircleCheck /><span
          ><b>正文提取</b><small>共 3 页，12 个段落</small></span
        >
      </div>
      <i></i>
      <div class="done">
        <CircleCheck /><span
          ><b>AI 分析</b><small>字段与通俗版已生成</small></span
        >
      </div>
      <i></i>
      <div class="active">
        <LoaderCircle /><span
          ><b>等待审核</b><small>请确认关键字段</small></span
        >
      </div>
    </section>
    <div v-if="fields.length" class="result-grid">
      <section class="panel">
        <div class="panel-title">
          <div>
            <h2>结构化字段</h2>
            <p>共识别 {{ fields.length }} 项关键内容</p>
          </div>
        </div>
        <div class="field-results">
          <article v-for="f in fields" :key="f.id">
            <span
              ><b>{{ f.label }}</b
              ><small
                >第 {{ f.page }} 页 · 可信度
                {{ Math.round(f.confidence * 100) }}%</small
              ></span
            >
            <p>{{ f.value }}</p>
          </article>
        </div>
      </section>
      <aside>
        <section class="panel plain">
          <div class="result-heading">
            <WandSparkles />
            <div>
              <h2>三句话看懂</h2>
              <p>通俗版摘要</p>
            </div>
          </div>
          <ol>
            <li>年满 80 周岁、符合条件的本市户籍老人可以申请这项补贴。</li>
            <li>
              准备好身份证、户口簿、银行卡和一寸照片，到户籍所在地社区办理。
            </li>
            <li>审核一般需要 10 个工作日，通过后补贴会发到本人银行卡。</li>
          </ol>
        </section>
        <section class="panel steps-mini">
          <div class="result-heading">
            <ListChecks />
            <div>
              <h2>办理步骤</h2>
              <p>{{ steps.length }} 个清楚步骤</p>
            </div>
          </div>
          <div v-for="(s, i) in steps.slice(0, 3)" :key="s[0]">
            <span>{{ i + 1 }}</span>
            <p>
              <b>{{ s[0] }}</b
              >{{ s[1] }}
            </p>
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>
