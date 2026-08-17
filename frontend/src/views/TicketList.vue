<template>
  <div>
    <el-card>
      <!-- 筛选区 -->
      <el-form inline>
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" placeholder="标题/描述" clearable style="width: 180px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="query.priority" placeholder="全部" clearable style="width: 100px">
            <el-option v-for="p in priorityOptions" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="query.category" placeholder="分类" clearable style="width: 100px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="reset">重置</el-button>
          <el-button type="success" @click="$router.push('/tickets/new')">新建工单</el-button>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table :data="records" v-loading="loading" stripe>
        <el-table-column prop="ticketNo" label="编号" width="150" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="90" />
        <el-table-column label="优先级" width="80">
          <template #default="{ row }">
            <el-tag :type="priorityType(row.priority)" size="small">{{ priorityLabel(row.priority) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customerName" label="客户" width="90" />
        <el-table-column prop="createTime" label="创建时间" width="165" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/tickets/${row.id}`)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        class="pager"
        layout="total, prev, pager, next, sizes"
        :total="total"
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :page-sizes="[10, 20, 50]"
        @change="load"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ticketApi } from '../api/ticket'

const statusOptions = [
  { value: 1, label: '新建' }, { value: 2, label: '待分派' }, { value: 3, label: '处理中' },
  { value: 4, label: '等待客户' }, { value: 5, label: '已解决' }, { value: 6, label: '已关闭' },
  { value: 7, label: '已升级' }, { value: 8, label: '已取消' }
]
const priorityOptions = [
  { value: 1, label: '紧急' }, { value: 2, label: '高' },
  { value: 3, label: '中' }, { value: 4, label: '低' }
]
const priorityMap = Object.fromEntries(priorityOptions.map(p => [p.value, p.label]))

const loading = ref(false)
const records = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, keyword: '', status: null, priority: null, category: '' })

const statusType = s => ({ 1: 'info', 2: 'warning', 3: 'primary', 4: 'info', 5: 'success', 6: 'info', 7: 'danger', 8: 'info' }[s] || 'info')
const priorityType = p => ({ 1: 'danger', 2: 'warning', 3: 'primary', 4: 'info' }[p] || 'info')
const priorityLabel = p => priorityMap[p] || p

async function load() {
  loading.value = true
  try {
    const data = await ticketApi.list(query)
    records.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.keyword = ''
  query.status = null
  query.priority = null
  query.category = ''
  query.page = 1
  load()
}

onMounted(load)
</script>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
