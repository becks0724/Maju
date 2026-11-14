// 119 반자동 연결 — 본문을 채운 문자 작성 화면(ACTION_SENDTO smsto:119)과 소방청 「119신고」 앱 열기. 보내기는 사람이 한다(G4)
package com.example.guardian.phone.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object Emergency119 {
    const val REPORT_APP = "com.winitech.mm119t"

    /** 작성 화면만 연다. 우리 앱은 119 로 문자를 보내지 않는다(SEND_SMS 로 119 를 부르는 코드 없음). */
    fun composeIntent(body: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:119")).putExtra("sms_body", body)

    fun openCompose(context: Context, body: String): Boolean = start(context, composeIntent(body))

    /** 「119신고」 앱 — 설치돼 있으면 실행, 없으면 스토어 상세. 신고는 사용자가 앱에서 한다. */
    fun openReportApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(REPORT_APP)
        return if (launch != null) start(context, launch)
        else start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$REPORT_APP")))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
