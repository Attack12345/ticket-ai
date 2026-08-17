<template>
  <el-card>
    <div class="head">
      <h3 style="display: inline-block">技能组管理</h3>
      <el-button type="primary" @click="openCreate">新建技能组</el-button>
    </div>
    <el-table :data="groups" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="agentCount" label="坐席数" width="90" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="openSetAgents(row)">设置坐席</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑 -->
    <el-dialog v-model="editDialog" :title="editing ? '编辑技能组' : '新建技能组'" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="50" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" maxlength="200" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 设置坐席 -->
    <el-dialog v-model="agentsDialog" :title="`设置坐席 - ${currentGroup?.name || ''}`" width="420px">
      <el-select v-model="selectedAgentIds" multiple style="width: 100%" placeholder="选择组内坐席">
        <el-option v-for="a in agents" :key="a.id" :label="`${a.name}（#${a.id}）`" :value="a.id" />
      </el-select>
      <template #footer>
        <el-button @click="agentsDialog = false">取消</el-button>
        <el-button type="primary" @click="saveAgents">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { skillGroupApi, agentApi } from '../api/manage'

const loading = ref(false)
const groups = ref([])
const agents = ref([])
const editDialog = ref(false)
const agentsDialog = ref(false)
const editing = ref(false)
const currentGroup = ref(null)
const selectedAgentIds = ref([])
const form = reactive({ name: '', description: '', status: 1 })

async function load() {
  loading.value = true
  try {
    groups.value = await skillGroupApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  form.name = ''
  form.description = ''
  form.status = 1
  editDialog.value = true
}

function openEdit(row) {
  editing.value = true
  form.name = row.name
  form.description = row.description
  form.status = row.status
  currentGroup.value = row
  editDialog.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入名称')
    return
  }
  if (editing.value) {
    await skillGroupApi.update(currentGroup.value.id, { ...form })
  } else {
    await skillGroupApi.create({ ...form })
  }
  ElMessage.success('保存成功')
  editDialog.value = false
  load()
}

async function openSetAgents(row) {
  currentGroup.value = row
  agents.value = await agentApi.list()
  selectedAgentIds.value = agents.value.filter(a => (a.groupIds || []).includes(row.id)).map(a => a.id)
  agentsDialog.value = true
}

async function saveAgents() {
  await skillGroupApi.setAgents(currentGroup.value.id, selectedAgentIds.value)
  ElMessage.success('坐席设置已保存')
  agentsDialog.value = false
  load()
}

async function remove(row) {
  await ElMessageBox.confirm(`确认删除技能组「${row.name}」？`, '删除确认', { type: 'warning' })
  await skillGroupApi.remove(row.id)
  ElMessage.success('已删除')
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
