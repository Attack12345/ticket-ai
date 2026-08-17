<template>
  <div>
    <el-card>
      <div class="head">
        <h3 style="display: inline-block">知识库</h3>
        <div>
          <el-input v-model="keyword" placeholder="检索知识库" clearable style="width: 220px; margin-right: 8px"
            @keyup.enter="doSearch" />
          <el-button type="primary" @click="doSearch">检索</el-button>
          <el-button type="success" style="margin-left: 8px" @click="openCreate">新建文章</el-button>
        </div>
      </div>

      <!-- 检索结果 -->
      <template v-if="searching">
        <el-empty v-if="!searchHits.length" description="无检索结果" />
        <el-card v-for="hit in searchHits" :key="hit.segmentId" shadow="hover" class="hit">
          <b>{{ hit.title }}</b>
          <span style="margin-left: 8px; color: #909399; font-size: 12px">{{ hit.category }}</span>
          <p class="hit-content">{{ hit.content }}</p>
          <span style="color: #909399; font-size: 12px">相关度：{{ hit.score?.toFixed(2) }}</span>
        </el-card>
      </template>

      <!-- 文章列表 -->
      <template v-else>
        <el-table :data="articles" v-loading="loading" stripe>
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
          <el-table-column prop="category" label="分类" width="100" />
          <el-table-column prop="viewCount" label="浏览" width="80" />
          <el-table-column prop="createTime" label="创建时间" width="165" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
          v-model:current-page="page" @change="loadArticles" />
      </template>
    </el-card>

    <!-- 新建/编辑 -->
    <el-dialog v-model="editDialog" :title="editing ? '编辑文章' : '新建文章'" width="700px" top="5vh">
      <el-form :model="form" label-width="60px">
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="200" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" maxlength="50" style="width: 200px" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="form.content" type="textarea" :rows="12" maxlength="100000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'

const loading = ref(false)
const articles = ref([])
const total = ref(0)
const page = ref(1)
const size = 10
const keyword = ref('')
const searching = ref(false)
const searchHits = ref([])

const editDialog = ref(false)
const editing = ref(false)
const currentId = ref(null)
const form = ref({ title: '', category: '', content: '' })

async function loadArticles() {
  loading.value = true
  searching.value = false
  try {
    const data = await request.get('/kb', { params: { page: page.value, size } })
    articles.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

async function doSearch() {
  if (!keyword.value.trim()) {
    loadArticles()
    return
  }
  searching.value = true
  searchHits.value = await request.post('/kb/search', {
    keyword: keyword.value, semantic: false, topN: 10
  })
}

function openCreate() {
  editing.value = false
  form.value = { title: '', category: '', content: '' }
  editDialog.value = true
}

function openEdit(row) {
  editing.value = true
  currentId.value = row.id
  form.value = { title: row.title, category: row.category || '', content: row.content }
  editDialog.value = true
}

async function save() {
  if (!form.value.title.trim() || !form.value.content.trim()) {
    ElMessage.warning('标题和内容不能为空')
    return
  }
  if (editing.value) {
    await request.put(`/kb/${currentId.value}`, form.value)
  } else {
    await request.post('/kb', form.value)
  }
  ElMessage.success('保存成功')
  editDialog.value = false
  loadArticles()
}

async function remove(row) {
  await ElMessageBox.confirm(`确认删除文章「${row.title}」？`, '删除确认', { type: 'warning' })
  await request.delete(`/kb/${row.id}`)
  ElMessage.success('已删除')
  loadArticles()
}

onMounted(loadArticles)
</script>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.hit {
  margin-bottom: 10px;
}
.hit-content {
  margin: 8px 0;
  color: #606266;
  font-size: 13px;
  line-height: 1.5;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
