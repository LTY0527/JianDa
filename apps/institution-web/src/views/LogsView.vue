<script setup lang="ts">
import PageHeader from "../components/PageHeader.vue";
import { Search } from "lucide-vue-next";
const logs = [
  ["李敏", "提交审核", "老年补贴申请指南", "成功", "2026-07-22 10:05"],
  ["系统", "完成 AI 处理", "老年补贴申请指南", "成功", "2026-07-22 09:40"],
  ["王芳", "发布内容", "社区养老服务申请", "成功", "2026-07-21 16:35"],
  [
    "平台管理员",
    "导入公开信息",
    "反诈提醒：警惕养老投资骗局",
    "成功",
    "2026-07-20 10:42",
  ],
];
</script>
<template>
  <div>
    <PageHeader
      title="操作日志"
      description="追踪关键业务操作，记录操作人、对象和结果。"
    />
    <section class="panel">
      <div class="filters">
        <div class="search">
          <Search /><input placeholder="搜索操作人或对象" />
        </div>
        <input type="date" value="2026-07-22" />
      </div>
      <table>
        <thead>
          <tr>
            <th>操作人</th>
            <th>操作</th>
            <th>对象</th>
            <th>结果</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in logs">
            <td>
              <b>{{ l[0] }}</b>
            </td>
            <td>{{ l[1] }}</td>
            <td>{{ l[2] }}</td>
            <td>
              <span class="success-text">● {{ l[3] }}</span>
            </td>
            <td>{{ l[4] }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
