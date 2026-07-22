<script setup lang="ts">
import PageHeader from "../components/PageHeader.vue";
import StatusTag from "../components/StatusTag.vue";
import { documents } from "../data/demo";
import {
  Upload,
  ArrowRight,
  FileClock,
  CircleCheck,
  BookOpen,
  TriangleAlert,
} from "lucide-vue-next";
</script>
<template>
  <div>
    <PageHeader
      title="上午好，李敏"
      description="这里是浦江街道社区服务中心今天的内容处理情况。"
      ><RouterLink to="/documents/upload" class="btn primary"
        ><Upload :size="17" />上传新材料</RouterLink
      ></PageHeader
    >
    <section class="metric-strip">
      <article>
        <span class="metric-icon blue"><FileClock /></span>
        <div>
          <small>待处理材料</small><strong>6</strong><em>2 项今日新增</em>
        </div>
      </article>
      <article>
        <span class="metric-icon orange"><TriangleAlert /></span>
        <div>
          <small>等待审核</small><strong>3</strong><em>最早等待 2 小时</em>
        </div>
      </article>
      <article>
        <span class="metric-icon green"><CircleCheck /></span>
        <div>
          <small>本月已发布</small><strong>18</strong><em>较上月增加 5 篇</em>
        </div>
      </article>
      <article>
        <span class="metric-icon teal"><BookOpen /></span>
        <div>
          <small>累计阅读</small><strong>1,286</strong><em>本周 328 次</em>
        </div>
      </article>
    </section>
    <div class="dashboard-grid">
      <section class="panel">
        <div class="panel-title">
          <div>
            <h2>近期材料</h2>
            <p>查看最新处理进度并继续下一步</p>
          </div>
          <RouterLink to="/documents"
            >查看全部 <ArrowRight :size="16"
          /></RouterLink>
        </div>
        <table>
          <thead>
            <tr>
              <th>材料名称</th>
              <th>状态</th>
              <th>更新时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in documents.slice(0, 3)" :key="d.id">
              <td>
                <b>{{ d.title }}</b
                ><small>{{ d.fileName }}</small>
              </td>
              <td><StatusTag :status="d.status" :text="d.statusText" /></td>
              <td>{{ d.updatedAt }}</td>
              <td>
                <RouterLink
                  :to="
                    d.status === 'WAITING_REVIEW'
                      ? `/documents/${d.id}/review`
                      : `/documents/${d.id}/process`
                  "
                  >继续处理</RouterLink
                >
              </td>
            </tr>
          </tbody>
        </table>
      </section>
      <aside class="panel todo">
        <div class="panel-title">
          <div>
            <h2>待办事项</h2>
            <p>需要您关注的工作</p>
          </div>
        </div>
        <ol>
          <li>
            <span>1</span>
            <div>
              <b>审核“老年补贴申请指南”</b
              ><small>AI 处理已完成，5 个关键字段待确认</small>
            </div>
          </li>
          <li>
            <span>2</span>
            <div>
              <b>检查门诊流程处理结果</b><small>预计 3 分钟后完成</small>
            </div>
          </li>
          <li>
            <span>3</span>
            <div>
              <b>更新已发布内容</b><small>1 篇内容来源信息需要更正</small>
            </div>
          </li>
        </ol>
      </aside>
    </div>
  </div>
</template>
