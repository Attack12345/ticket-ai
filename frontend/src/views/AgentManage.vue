<template>
  <el-card>
    <div class="head">
      <h3 style="display: inline-block">坐席管理</h3>
      <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 130px" @change="load">
        <el-option label="在线" :value="1" />
        <el-option label="离线" :value="0" />
      </el-select>
    </div>
    <el-table :data="agents" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="坐席" width="120" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="currentLoad" label="当前负载" width="90" />
      <el-table-column label="技能标签" min-width="180">
        <template #default="{ row }">
          <el-tag v-for="tag in row.skillTags" :key="tag" size="small" style="margin-right: 4px">{{ tag }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="groupIds" label="所属组" min-width="120">
        <template #default="{ row }">#{{ (row.groupIds || []).join(', #') || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link :type="row.status === 1 ? 'warning' : 'success'" size="small"
            @click="toggleStatus(row)">
            {{ row.status === 1 ? '下线' : '上线' }}
          </el-button>
          <el-button link type="primary" size="small" @click="editTags(row)">技能标签</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="tagDialog" title="编辑技能标签" width="420px">
      <el-select v-model="editTagsValue" multiple filterable allow-create default-first-option
        style="width: 100%" placeholder="输入后回车添加">
        <el-option v-for="t in editTagsValue" :key="t" :label="t" :value="t" />
      </el-select>
      <template #footer>
        <el-button @click="tagDialog = false">取消</el-button>
        <el-button type="primary" @click="saveTags">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { agentApi } from '../api/manage'

const loading = ref(false)
const agents = ref([])
const filterStatus = ref(null)
const tagDialog = ref(false)
const editTagsValue = ref([])
const editingAgent = ref(null)

async function load() {
  loading.value = true
  try {
    agents.value = await agentApi.list({ status: filterStatus.value ?? undefined })
  } finally {
    loading.value = false
  }
}

async function toggleStatus(row) {
  const next = row.status === 1 ? 0 : 1
  await agentApi.updateStatus(row.id, next)
  ElMessage.success(next === 1 ? '已上线' : '已下线')
  load()
}

function editTags(row) {
  editingAgent.value = row
  editTagsValue.value = [...(row.skillTags || [])]
  tagDialog.value = true
}

async function saveTags() {
  await agentApi.updateSkillTags(editingAgent.value.id, editTagsValue.value)
  ElMessage.success('技能标签已更新')
  tagDialog.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
</style>
