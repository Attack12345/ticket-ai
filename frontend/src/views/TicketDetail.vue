<template>
  <div v-loading="loading">
    <el-card v-if="ticket">
      <!-- 标题区 + 操作按钮（由后端 allowedEvents 驱动，DEV_DOC §7.2.3） -->
      <div class="head">
        <div>
          <h2 style="display: inline-block; margin-right: 12px">{{ ticket.title }}</h2>
          <el-tag :type="statusType(ticket.status)" size="small">{{ ticket.statusText }}</el-tag>
        </div>
        <div class="actions">
          <el-button v-for="btn in actionButtons" :key="btn.event" :type="btn.type" size="small" @click="doAction(btn)">
            {{ btn.label }}
          </el-button>
          <el-button size="small" type="primary" plain @click="openAiDrawer">AI 建议</el-button>
        </div>
      </div>

      <el-descriptions :column="3" border class="desc">
        <el-descriptions-item label="工单编号">{{ ticket.ticketNo }}</el-descriptions-item>
        <el-descriptions-item label="分类">{{ ticket.category || '未分类' }}</el-descriptions-item>
        <el-descriptions-item label="优先级">
          <el-tag :type="priorityType(ticket.priority)" size="small">{{ priorityLabel(ticket.priority) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="客户">{{ ticket.customerName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="联系方式">{{ ticket.customerContact || '-' }}</el-descriptions-item>
        <el-descriptions-item label="处理坐席">{{ ticket.agentId ? `#${ticket.agentId}` : '未分派' }}</el-descriptions-item>
        <el-descriptions-item label="响应截止">{{ ticket.firstResponseDeadline || '-' }}</el-descriptions-item>
        <el-descriptions-item label="解决截止">{{ ticket.resolveDeadline || '-' }}</el-descriptions-item>
        <el-descriptions-item label="分派策略">{{ ticket.assignStrategy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="AI 分类建议" :span="2">
          {{ ticket.aiCategory ? `${ticket.aiCategory}（置信度 ${ticket.aiScore}）` : '无' }}
          <el-button v-if="ticket.aiCategory && !ticket.category" link type="primary" size="small" @click="acceptCategory">
            采纳
          </el-button>
        </el-descriptions-item>
        <el-descriptions-item label="首次响应">{{ ticket.firstRespondedAt || '-' }}</el-descriptions-item>
      </el-descriptions>

      <h4 style="margin: 16px 0 8px">问题描述</h4>
      <p class="desc-text">{{ ticket.description || '无' }}</p>

      <!-- 回复区（仅处理中/等待客户可见） -->
      <template v-if="canReply">
        <h4 style="margin: 16px 0 8px">回复客户</h4>
        <el-input v-model="replyContent" type="textarea" :rows="3" placeholder="输入回复内容" maxlength="2000" show-word-limit />
        <div style="margin-top: 8px">
          <el-radio-group v-model="replyVisibility">
            <el-radio value="ALL">客户可见</el-radio>
            <el-radio value="INTERNAL">仅内部</el-radio>
          </el-radio-group>
          <el-button type="primary" style="margin-left: 12px" @click="sendReply">发送回复</el-button>
        </div>
      </template>

      <!-- 时间线 -->
      <h4 style="margin: 20px 0 8px">流转时间线</h4>
      <el-timeline>
        <el-timeline-item v-for="log in timeline" :key="log.id" :timestamp="log.createTime" placement="top">
          <div>
            <b>{{ log.fromStatusText }} → {{ log.toStatusText }}</b>
            <span style="margin-left: 8px; color: #909399">{{ log.event }}</span>
            <div style="color: #606266; font-size: 13px">
              {{ log.operatorType === 'SYSTEM' ? '系统' : `操作人 #${log.operatorId}` }}{{ log.remark ? ' · ' + log.remark : '' }}
            </div>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>

    <!-- AI 建议抽屉（DEV_DOC §7.2.4） -->
    <el-drawer v-model="aiDrawer" title="AI 回复建议" size="480px">
      <div v-loading="aiLoading">
        <el-alert v-if="aiError" :title="aiError" type="warning" :closable="false" style="margin-bottom: 12px" />
        <template v-if="aiSuggest">
          <h4>回复草稿（可编辑）</h4>
          <el-input v-model="aiSuggest.reply" type="textarea" :rows="8" />
          <h4 style="margin-top: 12px">引用来源</h4>
          <ul class="refs">
            <li v-for="ref in aiSuggest.kbRefs" :key="ref">{{ ref }}</li>
          </ul>
          <el-button type="primary" style="margin-top: 16px; width: 100%" @click="sendAiReply">发送回复</el-button>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ticketApi } from '../api/ticket'
import { useUserStore } from '../stores/user'

const route = useRoute()
const userStore = useUserStore()
const ticketId = route.params.id

const loading = ref(false)
const ticket = ref(null)
const timeline = ref([])
const replyContent = ref('')
const replyVisibility = ref('ALL')

const aiDrawer = ref(false)
const aiLoading = ref(false)
const aiError = ref('')
const aiSuggest = ref(null)

// 事件 → 按钮（后端 allowedEvents 为准，前端仅展示层控制）
const ACTION_DEFS = {
  CLAIM: { label: '领取', type: 'primary', api: 'claim' },
  MANUAL_ASSIGN: { label: '分派', type: 'warning', api: 'assign' },
  REPLY: { label: '回复', type: 'success', api: 'reply' },
  RESOLVE: { label: '解决', type: 'success', api: 'resolve' },
  CLOSE: { label: '关闭', type: 'warning', api: 'close' },
  REOPEN: { label: '重开', type: 'primary', api: 'reopen' },
  ESCALATE: { label: '升级', type: 'danger', api: 'escalate' },
  CANCEL: { label: '取消', type: 'danger', api: 'cancel' }
}

const actionButtons = computed(() => {
  const allowed = ticket.value?.allowedEvents || []
  // 系统事件不展示；按权限过滤
  return allowed
    .filter(e => ACTION_DEFS[e] && !['AUTO_ASSIGN', 'TIMEOUT_ESCALATE', 'CUSTOMER_REPLY', 'SUBMIT'].includes(e))
    .map(e => ACTION_DEFS[e])
})

const canReply = computed(() =>
  ticket.value && (ticket.value.status === 3 || ticket.value.status === 4))

const statusType = s => ({ 1: 'info', 2: 'warning', 3: 'primary', 4: 'info', 5: 'success', 6: 'info', 7: 'danger', 8: 'info' }[s] || 'info')
const priorityType = p => ({ 1: 'danger', 2: 'warning', 3: 'primary', 4: 'info' }[p] || 'info')
const priorityLabel = p => ({ 1: '紧急', 2: '高', 3: '中', 4: '低' }[p] || p)

async function load() {
  loading.value = true
  try {
    ticket.value = await ticketApi.detail(ticketId)
    timeline.value = await ticketApi.timeline(ticketId)
  } finally {
    loading.value = false
  }
}

async function doAction(btn) {
  if (btn.api === 'assign') {
    const { value } = await ElMessageBox.prompt('输入坐席 ID', '手动分派', {
      inputPattern: /^\d+$/,
      inputErrorMessage: '坐席 ID 必须为数字'
    })
    await ticketApi.assign(ticketId, Number(value))
  } else if (btn.api === 'reply') {
    if (!replyContent.value.trim()) {
      ElMessage.warning('请先输入回复内容')
      return
    }
    await sendReply()
    return
  } else {
    await ticketApi[btn.api](ticketId)
  }
  ElMessage.success(`${btn.label}成功`)
  load()
}

async function sendReply() {
  if (!replyContent.value.trim()) {
    ElMessage.warning('请输入回复内容')
    return
  }
  await ticketApi.reply(ticketId, { content: replyContent.value, visibility: replyVisibility.value })
  ElMessage.success('回复已发送')
  replyContent.value = ''
  load()
}

async function acceptCategory() {
  await ticketApi.acceptCategory(ticketId, {
    category: ticket.value.aiCategory,
    priority: ticket.value.aiPriority || ticket.value.priority
  })
  ElMessage.success('已采纳 AI 分类')
  load()
}

async function openAiDrawer() {
  aiDrawer.value = true
  aiLoading.value = true
  aiError.value = ''
  aiSuggest.value = null
  try {
    aiSuggest.value = await ticketApi.aiSuggest(ticketId)
  } catch (e) {
    aiError.value = e.message || 'AI 服务暂不可用'
  } finally {
    aiLoading.value = false
  }
}

async function sendAiReply() {
  await ticketApi.reply(ticketId, { content: aiSuggest.value.reply, visibility: 'ALL' })
  ElMessage.success('AI 建议已发送')
  aiDrawer.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.desc {
  margin-top: 8px;
}
.desc-text {
  color: #303133;
  line-height: 1.6;
  white-space: pre-wrap;
}
.refs {
  padding-left: 20px;
  color: #606266;
}
</style>
