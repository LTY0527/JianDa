<script setup lang="ts">
import { ref } from "vue";
import H5Header from "../components/H5Header.vue";
import BottomNav from "../components/BottomNav.vue";
import { Type, Volume2, Contrast } from "lucide-vue-next";
const size = ref(Number(localStorage.getItem("jianda_font") || 18));
const rate = ref(Number(localStorage.getItem("jianda_rate") || 0.9));
const contrast = ref(localStorage.getItem("jianda_contrast") === "1");
function save() {
  localStorage.setItem("jianda_font", String(size.value));
  localStorage.setItem("jianda_rate", String(rate.value));
  localStorage.setItem("jianda_contrast", contrast.value ? "1" : "0");
}
</script>
<template>
  <div class="h5-page" :class="{ contrast }">
    <H5Header />
    <main class="h5-main settings">
      <div class="simple-head">
        <h1>阅读设置</h1>
        <p>调整到您看得舒服、听得清楚。</p>
      </div>
      <section>
        <header>
          <Type />
          <div>
            <h2>正文字号</h2>
            <p>当前 {{ size }} 像素</p>
          </div>
        </header>
        <div class="font-options">
          <button
            v-for="n in [18, 20, 22, 24]"
            :class="{ active: size === n }"
            @click="
              size = n;
              save();
            "
            :style="{ fontSize: n + 'px' }"
          >
            {{ n }}
          </button>
        </div>
        <p class="preview" :style="{ fontSize: size + 'px' }">
          这是一段阅读效果示例。办事前，请准备好身份证和户口簿。
        </p>
      </section>
      <section>
        <header>
          <Volume2 />
          <div>
            <h2>语音速度</h2>
            <p>选择适合您的朗读速度</p>
          </div>
        </header>
        <div class="rate-options">
          <button
            v-for="r in [0.7, 0.9, 1.1]"
            :class="{ active: rate === r }"
            @click="
              rate = r;
              save();
            "
          >
            {{ r === 0.7 ? "较慢" : r === 0.9 ? "适中" : "较快" }}
          </button>
        </div>
      </section>
      <section>
        <header>
          <Contrast />
          <div>
            <h2>高对比度</h2>
            <p>加深文字和边框，更容易看清</p>
          </div>
          <label class="switch"
            ><input type="checkbox" v-model="contrast" @change="save" /><i></i
          ></label>
        </header>
      </section>
    </main>
    <BottomNav />
  </div>
</template>
