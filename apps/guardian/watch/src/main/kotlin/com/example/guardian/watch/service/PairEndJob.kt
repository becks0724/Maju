// 끝낼 토큰 재시도를 언제 돌릴지 — 앱 실행 · 감시 시작 때 바로 한 번, 남으면 네트워크가 있을 때 도는 JobScheduler 작업(플랫폼 API, 새 의존성 없음)
package com.example.guardian.watch.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

object PairEnd {
    private const val TAG = "GuardianWatch"
    private const val JOB_ID = 4_201

    /** 네트워크가 있어도 중계가 실패를 주는 동안 곧바로 다시 돌지 않게 두는 간격. 그 뒤는 JobScheduler 의 물러서기를 따른다. */
    private const val RETRY_DELAY_MS = 30_000L

    /** 동시에 두 곳(화면 · 서비스 · 작업)에서 불러도 같은 토큰을 한 번에 하나만 보낸다. */
    private val lock = Any()

    /** 끝낼 토큰이 있으면 백그라운드에서 한 번 시도하고, 남으면 네트워크 조건 작업을 예약한다(앱 실행 · 감시 시작 · 철회 직후). */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (PairStore(app).endPending.isEmpty()) return
        Thread { if (runNow(app)) schedule(app) }.start()
    }

    /** 백그라운드 스레드에서만. 남았으면 true. */
    fun runNow(ctx: Context): Boolean = synchronized(lock) {
        val store = PairStore(ctx)
        if (store.endPending.isEmpty()) return false
        val net = RelayNet(ctx)
        try {
            net.acquire()
            net.awaitWifi(3_000)
            PairEndRetry(store, net.api::end, System::currentTimeMillis).runOnce()
        } catch (e: RuntimeException) {
            // URL 형식 등 IOException 밖의 예외 — 토큰은 남겨 두고 다음 기회에
            Log.i(TAG, "끝낼 토큰 재시도 오류 ${e::class.java.simpleName}")
            true
        } finally {
            net.release()
        }
    }

    /** 네트워크가 있을 때 [PairEndJob] 을 돌린다. 재부팅을 넘어 남는다(RECEIVE_BOOT_COMPLETED). 같은 번호면 앞 예약을 바꾼다. */
    private fun schedule(ctx: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, PairEndJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(RETRY_DELAY_MS)
            .setPersisted(true)
            .build()
        runCatching { ctx.getSystemService(JobScheduler::class.java).schedule(job) }
            .onFailure { Log.i(TAG, "끝낼 토큰 작업 예약 실패 ${it::class.java.simpleName}") }
    }
}

/** 네트워크가 돌아오면 끝낼 토큰을 다시 부른다. 남으면 JobScheduler 의 물러서기 간격으로 다시 돈다(24시간이 지난 토큰은 지우고 끝). */
class PairEndJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread { jobFinished(params, PairEnd.runNow(this)) }.start()
        return true
    }

    /** 조건이 깨져 도중에 멈추면 다시 예약한다(끝낸 토큰은 이미 지웠다). */
    override fun onStopJob(params: JobParameters): Boolean = true
}
