<template>
  <el-card>
    <h3 style="margin-bottom: 16px">新建工单</h3>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" style="max-width: 560px">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="描述" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="5" maxlength="5000" show-word-limit />
      </el-form-item>
      <el-form-item label="分类">
        <el-select v-model="form.category" clearable style="width: 200px">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="优先级">
        <el-radio-group v-model="form.priority">
          <el-radio :value="1">紧急</el-radio>
          <el-radio :value="2">高</el-radio>
          <el-radio :value="3">中</el-radio>
          <el-radio :value="4">低</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="客户名">
        <el-input v-model="form.customerName" maxlength="50" />
      </el-form-item>
      <el-form-item label="联系方式">
        <el-input v-model="form.customerContact" maxlength="100" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="submitting" @click="submit">提交</el-button>
        <el-button @click="$router.back()">返回</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ticketApi } from '../api/ticket'

const router = useRouter()
const formRef = ref()
const submitting = ref(false)
const categories = ['售后', '售前', '投诉', '咨询', '其他']

const form = reactive({
  title: '',
  description: '',
  category: '',
  priority: 3,
  customerName: '',
  customerContact: '',
  channelId: 1
})

const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }]
}

async function submit() {
  await formRef.value.validate()
  submitting.value = true
  try {
    const data = await ticketApi.create(form)
    ElMessage.success(`创建成功：${data.ticketNo}`)
    router.push(`/tickets/${data.id}`)
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}
</script>
