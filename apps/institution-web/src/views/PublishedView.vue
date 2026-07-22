<script setup lang="ts">
import PageHeader from "../components/PageHeader.vue";
import StatusTag from "../components/StatusTag.vue";
import { Search, ExternalLink, RotateCcw } from "lucide-vue-next";
const rows = [
  ["老年补贴申请指南", "养老", "浦江街道社区服务中心", "2026-07-22", "386"],
  ["社区养老服务申请", "养老", "浦江街道社区服务中心", "2026-07-21", "241"],
  [
    "反诈提醒：警惕养老投资骗局",
    "反诈",
    "简达平台运营中心",
    "2026-07-20",
    "659",
  ],
];
</script>
<template>
  <div>
    <PageHeader
      title="已发布内容"
      description="管理用户端正在展示的指南和权威资讯。"
    />
    <section class="panel">
      <div class="filters">
        <div class="search"><Search /><input placeholder="搜索标题" /></div>
        <select>
          <option>全部分类</option>
          <option>养老</option>
          <option>健康</option>
          <option>反诈</option>
        </select>
      </div>
      <table>
        <thead>
          <tr>
            <th>标题</th>
            <th>分类</th>
            <th>来源</th>
            <th>发布日期</th>
            <th>阅读量</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows">
            <td>
              <b>{{ r[0] }}</b>
            </td>
            <td>{{ r[1] }}</td>
            <td>{{ r[2] }}</td>
            <td>{{ r[3] }}</td>
            <td>{{ r[4] }}</td>
            <td><StatusTag status="PUBLISHED" text="已发布" /></td>
            <td><a>编辑</a> · <a class="danger">撤回</a></td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
