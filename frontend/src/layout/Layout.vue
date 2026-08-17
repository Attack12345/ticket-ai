<template>
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">TicketAI</div>
      <el-menu :default-active="$route.path" router background-color="#1f3b73" text-color="#cfd8e8" active-text-color="#ffffff">
        <el-menu-item index="/tickets">
          <el-icon><Tickets /></el-icon><span>工单管理</span>
        </el-menu-item>
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon><span>统计看板</span>
        </el-menu-item>
        <el-menu-item index="/kb">
          <el-icon><Notebook /></el-icon><span>知识库</span>
        </el-menu-item>
        <el-menu-item index="/agents">
          <el-icon><UserFilled /></el-icon><span>坐席管理</span>
        </el-menu-item>
        <el-menu-item index="/skill-groups">
          <el-icon><Grid /></el-icon><span>技能组</span>
        </el-menu-item>
        <el-menu-item index="/sla">
          <el-icon><Timer /></el-icon><span>SLA 策略</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="welcome">欢迎，{{ userStore.username }}</span>
        <el-button link type="danger" @click="onLogout">退出登录</el-button>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()

function onLogout() {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout {
  height: 100%;
}
.aside {
  background: #1f3b73;
}
.logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-size: 20px;
  font-weight: 600;
}
.aside :deep(.el-menu) {
  border-right: none;
}
.header {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.welcome {
  color: #606266;
}
.main {
  background: #f5f7fa;
}
</style>
