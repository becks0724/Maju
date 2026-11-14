// 감시 알림 Ongoing Activity 시험 — 상태 글자 「운전 중」, 알림 생성 함수가 build 전에 OngoingActivity 를 적용하는지, 끝낼 때 알림과 함께 지우는지 본다
package com.example.guardian.watch

import com.example.guardian.watch.service.monitorOngoingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OngoingActivityTest {
    private fun src(path: String) = File(path).also { assertTrue("시험은 :watch 모듈 폴더에서 돈다 — ${it.absolutePath}", it.isFile) }.readText()
    private val service by lazy { src("src/main/kotlin/com/example/guardian/watch/service/MonitorService.kt") }
    private fun fn(name: String) = Regex("""private fun $name\(\)[^{]*\{[\s\S]*?\n    \}""").find(service)?.value
        .also { assertTrue("$name 없음", it != null) }!!

    @Test fun statusIsDriving() {
        assertEquals(listOf("운전 중"), monitorOngoingStatus().templates.map { it.toString() })
    }

    @Test fun monitorNotificationAppliesOngoingActivityBeforeBuild() {
        val body = fn("monitorNotification")
        val apply = body.indexOf(".apply(applicationContext)")
        val build = body.indexOf("return builder.build()")
        assertTrue("OngoingActivity 를 감시 알림 번호 · 같은 builder 로 만들어야 한다", body.contains("OngoingActivity.Builder(applicationContext, NOTIFY_MONITOR, builder)"))
        assertTrue("상태 글자는 monitorOngoingStatus()", body.contains(".setStatus(monitorOngoingStatus())"))
        assertTrue("시계 화면 아이콘은 차 아이콘", body.contains(".setStaticIcon(R.drawable.ic_ongoing_car)"))
        assertTrue("누르면 알림과 같은 MainActivity PendingIntent", body.contains(".setTouchIntent(pi)") && body.contains("MainActivity::class.java"))
        assertTrue("PendingIntent 는 FLAG_IMMUTABLE", body.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertTrue("apply 는 알림 build 전에 불러야 알림에 실린다", apply in 0 until build)
        // PM 결정 11-08 — 카테고리 · 제목은 그대로
        assertTrue(body.contains(".setCategory(NotificationCompat.CATEGORY_SERVICE)"))
        assertTrue(body.contains(".setContentTitle(WatchText.NOTIFICATION_MONITOR.ko)"))
        assertTrue("Ongoing Activity 는 ongoing 알림에만 붙는다", body.contains(".setOngoing(true)"))
    }

    @Test fun endRemovesForegroundNotification() {
        // Ongoing Activity 는 감시 알림에 묶여 있어, 끝낼 때 알림을 지우면 표시도 함께 사라진다
        assertTrue(fn("end").contains("ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)"))
    }

    @Test fun carIconExists() {
        assertTrue(src("src/main/res/drawable/ic_ongoing_car.xml").contains("<vector"))
    }
}
