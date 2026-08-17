<template>
  <div v-loading="loading">
    <!-- 指标卡 -->
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-num">{{ stats.todayNew ?? 0 }}</div>
          <div class="stat-label">今日新增工单</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-num">{{ stats.todayResolved ?? 0 }}</div>
          <div class="stat-label">今日解决</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-num">{{ fmtRate(stats.slaOnTimeRate) }}</div>
          <div class="stat-label">SLA 按时率</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-num">{{ stats.avgFirstResponseMinutes ?? '-' }}</div>
          <div class="stat-label">平均首次响应(分钟)</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 状态分布 -->
    <el-card style="margin-top: 16px">
      <h4 style="margin-bottom: 12px">工单状态分布</h4>
      <el-row :gutter="16">
        <el-col :span="8">
          <div ref="pieRef" style="height: 300px"></div>
        </el-col>
        <el-col :span="16">
          <el-table :data="statusRows" size="small" stripe>
            <el-table-column prop="label" label="状态" />
            <el-table-column prop="count" label="数量" width="120" />
            <el-table-column label="占比" min-width="200">
              <template #default="{ row }">
                <el-progress :percentage="row.percent" />
              </template>
            </el-table-column>
          </el-table>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import request from '../api/request'

const loading = ref(false)
const stats = ref({})
const pieRef = ref()
let chart = null

const statusMap = { 1: '新建', 2: '待分派', 3: '处理中', 4: '等待客户', 5: '已解决', 6: '已关闭', 7: '已升级', 8: '已取消' }
const totalCount = computed(() =>
  Object.values(stats.value.totalByStatus || {}).reduce((a, b) => a + b, 0))

const statusRows = computed(() => {
  const map = stats.value.totalByStatus || {}
  return Object.entries(map)
    .map(([code, count]) => ({
      label: statusMap[code] || `状态${code}`,
      count,
      percent: totalCount.value ? Math.round((count / totalCount.value) * 100) : 0
    }))
    .sort((a, b) => b.count - a.count)
})

function fmtRate(rate) {
  return rate == null ? '-' : `${Math.round(rate * 100)}%`
}

function renderPie() {
  if (!pieRef.value || !stats.value.totalByStatus) return
  const data = Object.entries(stats.value.totalByStatus).map(([code, count]) => ({
    name: statusMap[code] || `状态${code}`,
    value: count
  }))
  chart = echarts.init(pieRef.value)
  chart.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['40%', '65%'],
      data,
      label: { formatter: '{b}: {c}' }
    }]
  })
}

async function load() {
  loading.value = true
  try {
    stats.value = await request.get('/dashboard/stats')
    renderPie()
  } finally {
    loading.value = false
  }
}

function onResize() {
  chart?.resize()
}

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
})
</script>

<style scoped>
.stat-card {
  text-align: center;
}
.stat-num {
  font-size: 32px;
  font-weight: 600;
  color: #1f3b73;
}
.stat-label {
  margin-top: 6px;
  color: #909399;
  font-size: 13px;
}
</style>
