<script setup>
const transport = [
  { k: '上传端点', v: '未批准（G04）' },
  { k: '方法', v: 'POST' },
  { k: 'Content-Type', v: 'text/plain; charset=utf-8' },
  { k: '认证', v: '未确认（X01）' },
  { k: '载荷', v: 'sec_uid 纯文本，一行一个' },
  { k: '单次上限', v: '10000 行' },
  { k: 'worker 超时', v: '240 秒' },
  { k: 'systemd 超时', v: '300 秒' }
]
</script>

<template>
  <div>
    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">DEVELOPER MODE</div>
          <div class="card-title">传输参数（只读）</div>
        </div>
        <span class="count-chip">只读</span>
      </div>
      <div class="dya-note">
        <b>开发者模式当前只读。</b>G04 安全三项与 X01 传输契约未解除，传输参数禁止修改，上传适配器保持 disabled。
      </div>
      <div class="kv">
        <div v-for="item in transport" :key="item.k" class="kv-item">
          <div class="kv-k">{{ item.k }}</div>
          <div class="kv-v">{{ item.v }}</div>
        </div>
      </div>
      <div class="dya-actions">
        <el-button disabled>保存参数</el-button>
        <el-button disabled>测试连通性</el-button>
        <span class="dya-hint">解除阻断后才能编辑</span>
      </div>
    </div>

    <div class="dya-card">
      <div class="card-head">
        <div>
          <div class="card-eyebrow">BOUNDARIES</div>
          <div class="card-title">边界</div>
        </div>
      </div>
      <ul class="dya-rules">
        <li>只允许改传输参数，不改采集逻辑与前后端代码</li>
        <li>不写入令牌、密码等敏感参数</li>
        <li>改动必须留审计，且不能在 17:50–18:35 发版窗口操作</li>
      </ul>
    </div>
  </div>
</template>
