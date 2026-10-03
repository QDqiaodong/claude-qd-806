<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { farmApi } from '../api'

const rooms = ref([])
const history = ref([])
const activeRoom = ref(null)
const loading = ref(false)
const drawerVisible = ref(false)
const reportVisible = ref(false)
const reportSaving = ref(false)
const pollTimer = ref(null)
const reportForm = reactive({ temperature: 8, humidity: 88, co2Ppm: 800, lightLux: 120, recordedAt: '' })

const speciesLimits = {
  金针菇: { temperature: [4, 12], humidity: [85, 95], co2_ppm: [0, 1200] },
  杏鲍菇: { temperature: [12, 18], humidity: [85, 95], co2_ppm: [0, 1500] },
  香菇: { temperature: [12, 20], humidity: [80, 95], co2_ppm: [0, 1500] },
  平菇: { temperature: [15, 22], humidity: [80, 95], co2_ppm: [0, 1800] }
}

const series = computed(() => [
  { key: 'temperature', label: '温度', color: '#827717', points: makePoints('temperature', 0, 30) },
  { key: 'humidity', label: '湿度', color: '#3e7e72', points: makePoints('humidity', 60, 100) },
  { key: 'co2Ppm', label: 'CO₂', color: '#c45b45', points: makePoints('co2Ppm', 0, 2600) }
])

async function load() {
  loading.value = true
  try { rooms.value = await farmApi.getEnvironmentDashboard() }
  catch (error) { ElMessage.error(error.message) }
  finally { loading.value = false }
}

async function openHistory(room) {
  activeRoom.value = room
  drawerVisible.value = true
  try { history.value = await farmApi.getEnvironmentHistory(room.room_id, 24) }
  catch (error) { ElMessage.error(error.message) }
}

function metricAlert(room, key) {
  const range = speciesLimits[room.species]?.[key]
  if (!range) return false
  const value = Number(room[key])
  return value < range[0] || value > range[1]
}

function gaugeStyle(room, key) {
  const value = Number(room[key])
  const max = key === 'temperature' ? 30 : key === 'humidity' ? 100 : 2500
  return {
    '--gauge-value': `${Math.min(100, value / max * 100) * 3.6}deg`,
    '--gauge-color': metricAlert(room, key) ? '#c5473b' : '#827717'
  }
}

function makePoints(key, min, max) {
  if (!history.value.length) return ''
  return history.value.map((item, index) => {
    const x = 30 + index * (660 / Math.max(1, history.value.length - 1))
    const value = Math.max(min, Math.min(max, Number(item[key])))
    const y = 166 - ((value - min) / (max - min)) * 126
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

function shortTime(value) {
  return value ? value.slice(11, 16) : '--:--'
}

function openReport(room) {
  activeRoom.value = room
  reportForm.temperature = Number(room.temperature)
  reportForm.humidity = Number(room.humidity)
  reportForm.co2Ppm = Number(room.co2_ppm)
  reportForm.lightLux = Number(room.light_lux || 0)
  reportForm.recordedAt = ''
  reportVisible.value = true
}

/** 填一组明显越限/恢复读数，方便快速演练三连开单、六连恢复闭环。 */
function fillPreset(over) {
  const limits = speciesLimits[activeRoom.value.species]
  if (over) {
    reportForm.temperature = Number(limits.temperature[1]) + 5
    reportForm.humidity = 90
    reportForm.co2Ppm = limits.co2_ppm[1] + 300
  } else {
    reportForm.temperature = (limits.temperature[0] + limits.temperature[1]) / 2
    reportForm.humidity = 88
    reportForm.co2Ppm = Math.min(900, limits.co2_ppm[1] - 100)
  }
}

async function submitReport() {
  reportSaving.value = true
  try {
    await farmApi.reportEnvironment({
      roomId: activeRoom.value.room_id,
      temperature: reportForm.temperature,
      humidity: reportForm.humidity,
      co2Ppm: reportForm.co2Ppm,
      lightLux: reportForm.lightLux,
      recordedAt: reportForm.recordedAt || null
    })
    ElMessage.success('读数已上报，处置状态已同步刷新')
    reportVisible.value = false
    await load()
    if (drawerVisible.value) {
      history.value = await farmApi.getEnvironmentHistory(activeRoom.value.room_id, 24)
    }
  } catch (error) { ElMessage.error(error.message) }
  finally { reportSaving.value = false }
}

onMounted(() => {
  load()
  pollTimer.value = setInterval(load, 15000)
})
onBeforeUnmount(() => pollTimer.value && clearInterval(pollTimer.value))
</script>

<template>
  <section class="page environment-page" v-loading="loading">
    <div class="page-heading">
      <div>
        <span class="eyebrow">LIVE GROW ROOMS</span>
        <h1>菇房环境仪表盘墙</h1>
        <p>以最新采样判断催蕾条件，点击任意菇房展开过去 24 小时环境曲线与越限处置状态。</p>
      </div>
      <div class="live-badge"><i></i>传感器在线 {{ rooms.length }}/{{ rooms.length }} · 15s 自动刷新</div>
    </div>

    <div class="room-wall">
      <article v-for="room in rooms" :key="room.room_id" class="room-card"
               :class="{ alarm: Number(room.over_limit) === 1, paused: room.disposal_status }"
               @click="openHistory(room)">
        <div v-if="room.disposal_status" class="alarm-corner disposal-corner">{{ room.disposal_status }}</div>
        <div v-else-if="Number(room.over_limit) === 1" class="alarm-corner">环境告警</div>
        <header>
          <div>
            <span class="room-code">{{ room.room_code }}</span>
            <h2>{{ room.species }}菇房</h2>
          </div>
          <span class="phase-chip" :class="{ 'phase-paused': room.disposal_status }">
            {{ room.disposal_status ? '环境暂停' : room.status }}
          </span>
        </header>

        <div class="gauge-row">
          <div class="mini-gauge-wrap">
            <div class="mini-gauge" :style="gaugeStyle(room, 'temperature')">
              <div><strong>{{ Number(room.temperature).toFixed(1) }}</strong><span>℃</span></div>
            </div>
            <b>温度</b>
          </div>
          <div class="mini-gauge-wrap">
            <div class="mini-gauge" :style="gaugeStyle(room, 'humidity')">
              <div><strong>{{ Number(room.humidity).toFixed(0) }}</strong><span>%</span></div>
            </div>
            <b>湿度</b>
          </div>
          <div class="mini-gauge-wrap">
            <div class="mini-gauge" :style="gaugeStyle(room, 'co2_ppm')">
              <div><strong>{{ room.co2_ppm }}</strong><span>ppm</span></div>
            </div>
            <b>CO₂</b>
          </div>
        </div>

        <footer>
          <span>光照 {{ room.light_lux }} lx</span>
          <span>采样 {{ shortTime(room.recorded_at) }}</span>
          <button @click.stop="openReport(room)">上报读数</button>
        </footer>
      </article>
    </div>

    <el-drawer v-model="drawerVisible" direction="btt" size="52%" :with-header="false" class="history-drawer">
      <div class="drawer-sheet" v-if="activeRoom">
        <div class="drawer-title">
          <div>
            <span class="eyebrow">24 HOURS TRACE</span>
            <h2>{{ activeRoom.room_code }} · {{ activeRoom.species }}环境曲线</h2>
          </div>
          <div class="series-legend">
            <span v-for="item in series" :key="item.key"><i :style="{ background: item.color }"></i>{{ item.label }}</span>
          </div>
        </div>
        <div class="line-chart">
          <svg viewBox="0 0 720 190" preserveAspectRatio="none" aria-label="24小时环境曲线">
            <line v-for="y in [40,82,124,166]" :key="y" x1="30" :y1="y" x2="690" :y2="y" class="grid-line" />
            <polyline v-for="item in series" :key="item.key" :points="item.points" :stroke="item.color" fill="none" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <div class="chart-axis"><span>{{ shortTime(history[0]?.recordedAt) }}</span><span>过去 24 小时</span><span>{{ shortTime(history[history.length - 1]?.recordedAt) }}</span></div>
        </div>
        <div class="trace-strip">
          <div v-for="item in history.slice(-8)" :key="item.id">
            <span>{{ shortTime(item.recordedAt) }}</span>
            <b>{{ item.temperature }}℃</b>
            <em>{{ item.humidity }}%</em>
            <i>{{ item.co2Ppm }}ppm</i>
          </div>
        </div>
        <div class="drawer-actions">
          <el-button type="primary" @click="openReport(activeRoom)">上报新读数</el-button>
          <span v-if="activeRoom.disposal_status" class="drawer-disposal-note">
            本菇房存在活动处置单：{{ activeRoom.disposal_status }}
          </span>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="reportVisible" :title="`上报读数 · ${activeRoom?.room_code || ''}`" width="520px" class="farm-dialog">
      <el-form label-position="top">
        <div class="report-presets">
          <el-button size="small" @click="fillPreset(true)">填入越限读数</el-button>
          <el-button size="small" @click="fillPreset(false)">填入适宜读数</el-button>
          <span>留空采集时间表示“当前时间”，填过去时间可模拟迟到/乱序读数</span>
        </div>
        <div class="three-inputs">
          <el-form-item label="温度 ℃"><el-input-number v-model="reportForm.temperature" :precision="1" :step="0.5" /></el-form-item>
          <el-form-item label="湿度 %"><el-input-number v-model="reportForm.humidity" :precision="1" :step="1" /></el-form-item>
          <el-form-item label="CO₂ ppm"><el-input-number v-model="reportForm.co2Ppm" :step="50" /></el-form-item>
        </div>
        <el-form-item label="光照 lx"><el-input-number v-model="reportForm.lightLux" :step="10" /></el-form-item>
        <el-form-item label="采集时间">
          <el-date-picker v-model="reportForm.recordedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          placeholder="留空 = 当前时间" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reportVisible = false">取消</el-button>
        <el-button type="primary" :loading="reportSaving" @click="submitReport">上报并对账</el-button>
      </template>
    </el-dialog>
  </section>
</template>
