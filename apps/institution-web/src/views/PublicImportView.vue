<script setup lang="ts">
import { ref } from "vue";
import PageHeader from "../components/PageHeader.vue";
import { Link, Plus, Info } from "lucide-vue-next";
const done = ref(false);
</script>
<template>
  <div class="narrow">
    <PageHeader
      title="权威公开信息导入"
      description="平台管理员可手工导入政府、医院和主流媒体的公开信息。"
    />
    <section class="panel form-panel">
      <div class="info-note">
        <Info /><span
          >本演示版本不访问真实网页。填写正文后将创建与上传材料相同的 AI
          处理任务。</span
        >
      </div>
      <label class="field"
        >信息标题<input value="高血压患者夏季健康管理提示"
      /></label>
      <div class="form-row">
        <label class="field">来源名称<input value="城市人民医院" /></label
        ><label class="field"
          >分类<select>
            <option>健康</option>
            <option>养老</option>
            <option>反诈</option>
            <option>生活服务</option>
          </select></label
        >
      </div>
      <label class="field"
        >来源链接
        <div class="input-icon">
          <Link /><input
            value="https://example.org/health/notice-2026"
          /></div></label
      ><label class="field"
        >发布时间<input type="date" value="2026-07-20" /></label
      ><label class="field"
        >正文内容<textarea rows="9">
夏季气温较高，高血压患者应按医嘱规律服药，不可自行停药或减量。建议每日早晚测量血压，注意补充水分，避免在午后高温时段长时间外出。如出现持续头痛、胸闷等不适，应及时就医。</textarea>
      </label>
      <div v-if="done" class="inline-success">
        已创建处理任务，可前往材料管理查看进度。
      </div>
      <div class="form-actions">
        <button class="btn primary" @click="done = true">
          <Plus />导入并创建 AI 任务
        </button>
      </div>
    </section>
  </div>
</template>
