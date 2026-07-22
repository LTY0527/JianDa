<script setup lang="ts">
import { ref } from "vue";
import { useRoute } from "vue-router";
import PageHeader from "../components/PageHeader.vue";
import { documentApi } from "../api/documents";
import { apiMessage } from "../api/http";
import { CheckCircle2, Eye, Send, ShieldCheck } from "lucide-vue-next";
const documentId = Number(useRoute().params.id);
const published = ref(false);
const error = ref("");
const submitting = ref(false);
async function publish() {
  submitting.value = true;
  error.value = "";
  try {
    await documentApi.publish(documentId, {
      title: "老年补贴申请指南",
      category: "养老",
      sourceName: "浦江街道社区服务中心",
      sourceUrl: "https://example.org/pujiang",
    });
    published.value = true;
  } catch (cause) {
    error.value = apiMessage(cause);
  } finally {
    submitting.value = false;
  }
}
</script>
<template>
  <div class="narrow">
    <PageHeader
      title="审核与发布"
      description="确认分类、来源和用户端展示效果后发布。"
    />
    <div v-if="published" class="success-panel">
      <CheckCircle2 />
      <h2>内容已成功发布</h2>
      <p>“老年补贴申请指南”已出现在用户端办事指南中。</p>
      <div>
        <RouterLink class="btn primary" to="/published"
          >查看已发布内容</RouterLink
        ><a
          class="btn secondary"
          href="http://localhost:5174/guide/elderly-subsidy"
          >打开用户端</a
        >
      </div>
    </div>
    <template v-else
      ><section class="panel publish-form">
        <div class="review-ok">
          <ShieldCheck /><span
            ><b>关键字段审核已完成</b
            ><small>5 个字段已确认，审核记录将随本次发布保存。</small></span
          >
        </div>
        <div class="form-row">
          <label class="field">发布标题<input value="老年补贴申请指南" /></label
          ><label class="field"
            >内容分类<select>
              <option>办事指南 / 养老</option>
              <option>健康</option>
              <option>生活服务</option>
            </select></label
          >
        </div>
        <label class="field"
          >摘要<textarea
            rows="3"
            value="年满 80 周岁的本市户籍老人，可准备身份证、户口簿等材料到社区申请生活补贴。"
          ></textarea>
        </label>
        <div class="form-row">
          <label class="field"
            >来源名称<input value="浦江街道社区服务中心" /></label
          ><label class="field"
            >公开日期<input type="date" value="2026-07-22"
          /></label>
        </div>
        <label class="check"
          ><input type="checkbox" checked />
          我已确认内容准确、来源有效，并同意在用户端公开。</label
        >
        <p v-if="error" class="form-error">{{ error }}</p>

        <div class="form-actions">
          <button class="btn secondary"><Eye :size="17" />用户端预览</button
          ><button class="btn primary" @click="publish" :disabled="submitting">
            <Send :size="17" />{{ submitting ? "正在发布…" : "审核通过并发布" }}
          </button>
        </div>
      </section></template
    >
  </div>
</template>
