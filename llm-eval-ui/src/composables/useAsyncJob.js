import { ref } from 'vue'
import { asyncJobApi } from '../api'

/**
 * 异步任务轮询 hook
 *
 * 用于 AI识别成规则 / 润色多维度 / 数据重复检测 这类耗时操作：
 *   1. await start(提交函数) 提交任务拿到 jobId，并持续轮询直到任务结束
 *   2. 返回 true = 任务已结束（COMPLETED 或 FAILED，看 status），false = 提交阶段失败
 *   3. COMPLETED 后自动拉取结果到 result，FAILED 时 errorMessage 有值
 *
 * 用法：
 *   const job = useAsyncJob()
 *   const ok = await job.start(() => promptApi.parseToDimensions(modelId, text))
 *   if (!ok) return
 *   applyConfig(job.result.value)
 *
 * 模板中如需展示进度，可对返回值包一层 reactive： const job = reactive(useAsyncJob())
 */
export function useAsyncJob() {
  const jobId = ref(null)
  const status = ref(null) // PENDING / RUNNING / COMPLETED / FAILED
  const progress = ref(0)
  const progressText = ref('')
  const errorMessage = ref('')
  const result = ref(null)
  const running = ref(false)

  const POLL_INTERVAL = 1500

  let pollTimer = null
  let settleResolve = null // start() 返回的 Promise 的 resolve（任务结束时调用）

  function reset() {
    stopPolling()
    jobId.value = null
    status.value = null
    progress.value = 0
    progressText.value = ''
    errorMessage.value = ''
    result.value = null
    running.value = false
    settleResolve = null
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  /** 提交异步任务，返回的 Promise 在任务结束（COMPLETED/FAILED）时 resolve */
  function start(submitFn) {
    reset()
    running.value = true
    return new Promise(resolve => {
      settleResolve = resolve
      doSubmit(submitFn, resolve)
    })
  }

  async function doSubmit(submitFn, resolve) {
    try {
      const { data: job } = await submitFn()
      jobId.value = job.id
      applyJob(job)
      if (status.value === 'COMPLETED') {
        await fetchResult()
        resolve(true)
      } else if (status.value === 'FAILED') {
        errorMessage.value = job.errorMessage || '任务执行失败'
        running.value = false
        resolve(true)
      } else {
        startPolling()
      }
    } catch (e) {
      // 提交阶段失败：错误消息已由拦截器统一提示
      running.value = false
      resolve(false)
    }
  }

  function applyJob(job) {
    status.value = job.status
    progress.value = job.progress ?? 0
    progressText.value = job.progressText || ''
  }

  async function poll() {
    if (!jobId.value) return
    try {
      const { data: job } = await asyncJobApi.get(jobId.value)
      applyJob(job)
      if (status.value === 'COMPLETED') {
        stopPolling()
        await fetchResult()
        settle()
      } else if (status.value === 'FAILED') {
        stopPolling()
        errorMessage.value = job.errorMessage || '任务执行失败'
        running.value = false
        settle()
      }
    } catch (e) {
      // 单次轮询失败不中断，等下一轮；持续失败由业务侧兜底
    }
  }

  function startPolling() {
    stopPolling()
    pollTimer = setInterval(poll, POLL_INTERVAL)
  }

  async function fetchResult() {
    if (!jobId.value) return
    try {
      const { data } = await asyncJobApi.getResult(jobId.value)
      result.value = data
    } catch (e) {
      errorMessage.value = e.message || '获取结果失败'
    } finally {
      running.value = false
      stopPolling()
    }
  }

  /** 结束 Promise（幂等） */
  function settle() {
    if (settleResolve) {
      settleResolve(true)
      settleResolve = null
    }
  }

  return {
    jobId,
    status,
    progress,
    progressText,
    errorMessage,
    result,
    running,
    start,
    reset
  }
}