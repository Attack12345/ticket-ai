<template>
  <el-card>
    <div class="head">
      <h3 style="display: inline-block">SLA 策略管理</h3>
      <el-button type="primary" @click="openCreate">新建策略</el-button>
    </div>
    <el-table :data="policies" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="策略名" width="120" />
      <el-table-column label="优先级" width="90">
        <template #default="{ row }">
          <el-tag :type="priorityType(row.priority)" size="small">{{ priorityLabel(row.priority) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="firstResponseMinutes" label="响应时限(分)" width="110" />
      <el-table-column prop="resolveMinutes" label="解决时限(分)" width="110" />
      <el-table-column label="自动升级" width="90">
        <template #default="{ row }">
          <el-tag :type="row.autoEscalate === 1 ? 'success' : 'info'" size="small">
            {{ row.autoEscalate === 1 ? '开启' : '关闭' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editDialog" :title="editing ? '编辑策略' : '新建策略'" width="460px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="策略名">
          <el-input v-model="form.name" maxlength="50" />
        </el-form-item>
        <el-form-item label="适用优先级">
          <el-select v-model="form.priority" style="width: 100%">
            <el-option v-for="p in priorityOptions" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="响应时限(分钟)">
          <el-input-number v-model="form.firstResponseMinutes" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="解决时限(分钟)">
          <el-input-number v-model="form.resolveMinutes" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="超时自动升级">
          <el-switch v-model="form.autoEscalate" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { slaApi } from '../api/manage'

const loading = ref(false)
const policies = ref([])
const editDialog = ref(false)
const editing = ref(false)
const currentId = ref(null)
const priorityOptions = [
  { value: 1, label: '1-紧急' }, { value: 2, label: '2-高' },
  { value: 3, label: '3-中' }, { value: 4, label: '4-低' }
]
const priorityMap = Object.fromEntries(priorityOptions.map(p => [p.value, p.label]))
const form = reactive({
  name: '', priority: 3, firstResponseMinutes: 120,
  resolveMinutes: 1440, autoEscalate: 1, status: 1
})

const priorityType = p => ({ 1: 'danger', 2: 'warning', 3: 'primary', 4: 'info' }[p] || 'info')
const priorityLabel = p => priorityMap[p] || p

async function load() {
  loading.value = true
  try {
    policies.value = await slaApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  Object.assign(form, {
    name: '', priority: 3, firstResponseMinutes: 120,
    resolveMinutes: 1440, autoEscalate: 1, status: 1
  })
  editDialog.value = true
}

function openEdit(row) {
  editing.value = true
  currentId.value = row.id
  Object.assign(form, {
    name: row.name, priority: row.priority,
    firstResponseMinutes: row.firstResponseMinutes,
    resolveMinutes: row.resolveMinutes,
    autoEscalate: row.autoEscalate, status: row.status
  })
  editDialog.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入策略名')
    return
  }
  if (editing.value) {
    await slaApi.update(currentId.value, { ...form })
  } else {
    await slaApi.create({ ...form })
  }
  ElMessage.success('保存成功')
  editDialog.value = false
  load()
}

async function remove(row) {
  await ElMessageBox.confirm(`确认删除策略「${row.name}」？`, '删除确认', { type: 'warning' })
  await slaApi.remove(row.id)
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
