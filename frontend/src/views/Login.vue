<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="title">TicketAI 智能客服工单系统</h2>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="onLogin">
            登 录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="hint">演示账号：admin / Admin@12345（管理员）、agent01 / Admin@12345（坐席）</div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import request from '../api/request'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function onLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    const data = await request.post('/auth/login', form)
    userStore.setTokens(data.accessToken, data.refreshToken)
    // 解码 accessToken 中的 username/permissions
    try {
      const payload = JSON.parse(atob(data.accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
      userStore.setUser(payload.username || form.username, payload.permissions || [])
    } catch (e) {
      userStore.setUser(form.username, [])
    }
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e) {
    // 错误已由拦截器提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f3b73 0%, #2d5ba8 100%);
}
.login-card {
  width: 400px;
  padding: 20px 10px;
  border-radius: 10px;
}
.title {
  text-align: center;
  margin-bottom: 24px;
  color: #1f3b73;
}
.login-btn {
  width: 100%;
}
.hint {
  font-size: 12px;
  color: #909399;
  text-align: center;
}
</style>
