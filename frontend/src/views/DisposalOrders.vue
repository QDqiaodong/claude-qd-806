<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { farmApi } from '../api'

const orders = ref([])
const rooms = ref([])
const loading = ref(false)
const filterStatus = ref('')
const detailVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)
const dialog = reactive({ visible: false, mode: 'accept', saving: false })
const form = reactive({ operator: '', expectedFinishAt: '', note: '' })
const pollTimer = ref(null)

const statuses = ['待接单', '处理中', '恢复待确认', '已解除']

const statusType = {
  待接单: 'danger',
  处理中: 'warning',
  恢复待确认: 'primary',
  已解除: 'success'
}

const filtered = computed(() => orders.value.filter(item => !filterStatus.value || item.status === filterStatus.value))
const activeOrder = computed(() => detail.value?.order || null)

function fmt(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '—'
}

async function load() {
  loading.value = true
  try {
    const [list, roomList] = await Promise.all([
      farmApi.getDisposalOrders({}),
      farmApi.getRooms()
    ])
    orders.value = list
    rooms.value = roomList
  } catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try { detail.value = await farmApi.getDisposalDetail(row.id) }
  catch (error) { ElMessage.error(error.message) }
  finally { detailLoading.value = false }
}

function openDialog(mode) {
  dialog.mode = mode
  dialog.visible = true
  form.operator = ''
  form.expectedFinishAt = ''
  form.note = ''
  if (mode === 'accept' && activeOrder.value?.expectedFinishAt) {
    form.expectedFinishAt = fmt(activeOrder.value.expectedFinishAt)
  }
}

const dialogTitle = computed(() => ({
  accept: '接单处理',
  progress: '填写处理记录',
  resolve: '确认解除并恢复生产'
}[dialog.mode]))

async function submit() {
  if (!form.operator.trim()) {
    ElMessage.error('请填写值班人员姓名')
    return
  }
  if (dialog.mode === 'accept' && !form.expectedFinishAt) {
    ElMessage.error('接单必须填写预计完成时间')
    return
  }
  if (dialog.mode === 'resolve' && !form.note.trim()) {
    ElMessage.error('解除前请填写处理/确认记录')
    return
  }
  dialog.saving = true
  const payload = {
    operator: form.operator.trim(),
    expectedFinishAt: form.expectedFinishAt || null,
    note: form.note.trim() || null
  }
  try {
    const action = {
      accept: farmApi.acceptDisposal,
      progress: farmApi.progressDisposal,
      resolve: farmApi.resolveDisposal
    }[dialog.mode]
    await action(activeOrder.value.id, payload)
    ElMessage.success({ accept: '接单成功', progress: '处理记录已保存', resolve: '处置单已解除' }[dialog.mode])
    dialog.visible = false
    await load()
    if (detailVisible.value) {
      detail.value = await farmApi.getDisposalDetail(activeOrder.value.id)
    }
  } catch (error) { ElMessage.error(error.message) }
  finally { dialog.saving = false }
}

function eventTag(type) {
  return ({
    触发: 'danger',
    接单: 'primary',
    处理记录: 'info',
    持续越限: 'warning',
    恶化: 'danger',
    恢复待确认: 'primary',
    再次越限退回: 'danger',
    解除: 'success'
  })[type] || 'info'
}

function metricText(metrics) {
  return metrics ? metrics.replaceAll(',', '、') : '—'
}

onMounted(() => {
  load()
  pollTimer.value = setInterval(load, 15000)
})
onBeforeUnmount(() => pollTimer.value && clearInterval(pollTimer.value))
</script>

<template>
  <section class="page disposal-page" v-loading="loading">
    <div class="page-heading disposal-heading">
      <div>
        <span class="eyebrow">ENVIRONMENT INCIDENT LOOP</span>
        <h1>环境越限处置闭环</h1>
        <p>连续三条越限自动开单并暂停在产批次；连续六条恢复待确认，期间反复全部留痕。</p>
      </div>
      <el-radio-group v-model="filterStatus" size="default">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button v-for="s in statuses" :key="s" :label="s">{{ s }}</el-radio-button>
      </el-radio-group>
    </div>

    <el-table :data="filtered" class="disposal-table" stripe @row-click="openDetail">
      <el-table-column prop="order_no" label="处置单号" width="170" />
      <el-table-column label="菇房" width="110">
        <template #default="{ row }">{{ row.room_code }} · {{ row.species }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusType[row.status]" effect="dark" round>{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="触发指标" width="130">
        <template #default="{ row }">{{ metricText(row.trigger_metrics) }}</template>
      </el-table-column>
      <el-table-column prop="over_count" label="越限读数" width="90" align="center" />
      <el-table-column label="恢复进度" width="130">
        <template #default="{ row }">
          <span :class="{ 'recovery-done': row.status === '已解除' || row.status === '恢复待确认' }">
            {{ Math.min(row.recovered_count || 0, 6) }}/6
          </span>
        </template>
      </el-table-column>
      <el-table-column label="经办人" width="100">
        <template #default="{ row }">{{ row.assignee || '待接单' }}</template>
      </el-table-column>
      <el-table-column label="触发时间" width="150">
        <template #default="{ row }">{{ fmt(row.triggered_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="openDetail(row)">查看时间线</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="detailVisible" direction="rtl" size="52%" :with-header="false" class="disposal-drawer">
      <div v-if="detail" class="disposal-sheet" v-loading="detailLoading">
        <div class="sheet-head">
          <div>
            <span class="eyebrow">DISPOSAL ORDER</span>
            <h2>{{ detail.order.orderNo }}</h2>
            <el-tag :type="statusType[detail.order.status]" effect="dark" round size="large">{{ detail.order.status }}</el-tag>
          </div>
          <div class="sheet-actions">
            <el-button v-if="!detail.order.assignee && (detail.order.status === '待接单' || detail.order.status === '处理中')"
                       type="primary" @click="openDialog('accept')">接单</el-button>
            <el-button v-if="detail.order.status === '处理中'" @click="openDialog('progress')">填写处理记录</el-button>
            <el-button v-if="detail.order.status === '恢复待确认'" type="primary" @click="openDialog('resolve')">
              确认解除
            </el-button>
            <el-tag v-if="detail.order.status === '已解除'" type="success" effect="plain">
              由 {{ detail.order.resolvedBy }} 于 {{ fmt(detail.order.resolvedAt) }} 解除
            </el-tag>
          </div>
        </div>

        <div class="sheet-grid">
          <div><span>菇房</span><b>{{ detail.roomCode }} · {{ detail.species }}</b></div>
          <div><span>触发指标</span><b>{{ metricText(detail.order.triggerMetrics) }}</b></div>
          <div><span>触发区间</span><b>{{ fmt(detail.order.triggerRecordedStart) }} ~ {{ fmt(detail.order.triggerRecordedEnd) }}</b></div>
          <div><span>触发读数</span><b>温度{{ detail.order.triggerTemperature }}℃ / 湿度{{ detail.order.triggerHumidity }}% / CO₂{{ detail.order.triggerCo2Ppm }}ppm</b></div>
          <div><span>经办人</span><b>{{ detail.order.assignee || '待接单' }}</b></div>
          <div><span>接单时间</span><b>{{ fmt(detail.order.acceptedAt) }}</b></div>
          <div><span>预计完成</span><b>{{ fmt(detail.order.expectedFinishAt) }}</b></div>
          <div><span>越限/恢复读数</span><b>{{ detail.order.overCount }} 条 / {{ Math.min(detail.order.recoveredCount || 0, 6) }} 条</b></div>
          <div v-if="detail.order.lastOverAt"><span>最近越限</span><b>
            {{ fmt(detail.order.lastOverAt) }} 温度{{ detail.order.lastOverTemperature }}℃ / 湿度{{ detail.order.lastOverHumidity }}% / CO₂{{ detail.order.lastOverCo2Ppm }}ppm
          </b></div>
          <div v-if="detail.order.resolutionNote"><span>解除备注</span><b>{{ detail.order.resolutionNote }}</b></div>
        </div>

        <div class="sheet-section">
          <h3>暂停的出菇批次（解除时仅恢复本单批次）</h3>
          <div v-for="item in detail.batches" :key="item.snapshot.id" class="batch-pill">
            <b>{{ item.snapshot.batchNo }}</b>
            <span>原状态：{{ item.snapshot.originalStatus }}</span>
            <el-tag v-if="item.snapshot.restoredAt" type="success" size="small">
              已恢复 {{ fmt(item.snapshot.restoredAt) }}
            </el-tag>
            <el-tag v-else size="small" :type="item.currentStatus === '环境暂停' ? 'warning' : 'info'">
              当前：{{ item.currentStatus }}
            </el-tag>
          </div>
        </div>

        <div class="sheet-section">
          <h3>处置时间线</h3>
          <el-timeline>
            <el-timeline-item v-for="event in detail.events" :key="event.id"
                              :type="eventTag(event.eventType)" :timestamp="fmt(event.occurredAt)" placement="top">
              <div class="event-card">
                <el-tag :type="eventTag(event.eventType)" size="small" effect="plain">{{ event.eventType }}</el-tag>
                <p>{{ event.detail }}</p>
                <small v-if="event.operator">经办：{{ event.operator }}</small>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="dialog.visible" :title="dialogTitle" width="520px" class="farm-dialog">
      <el-form label-position="top">
        <el-form-item label="值班人员" required>
          <el-input v-model="form.operator" placeholder="输入值班人员姓名" maxlength="40" />
        </el-form-item>
        <el-form-item v-if="dialog.mode !== 'progress'" :label="dialog.mode === 'accept' ? '预计完成时间' : '预计完成时间（可选）'"
                      :required="dialog.mode === 'accept'">
          <el-date-picker v-model="form.expectedFinishAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          placeholder="选择预计完成时间" style="width:100%" />
        </el-form-item>
        <el-form-item v-else label="调整预计完成时间（可选）">
          <el-date-picker v-model="form.expectedFinishAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          placeholder="不调整可留空" style="width:100%" />
        </el-form-item>
        <el-form-item :label="dialog.mode === 'resolve' ? '解除/确认记录' : '处理记录'" :required="dialog.mode === 'resolve'">
          <el-input v-model="form.note" type="textarea" :rows="3" maxlength="500" show-word-limit
                    :placeholder="dialog.mode === 'resolve' ? '确认环境已恢复适宜，说明现场处置结果' : '记录已采取的措施与现场情况'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submit">
          {{ { accept: '确认接单', progress: '保存记录', resolve: '确认解除' }[dialog.mode] }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
