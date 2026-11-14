// Flow 의 선택 필드 localTest 시험 — 참일 때만 싣고, 생략 · 모르는 필드는 거짓으로 읽으며, 상태 기계 스냅숏에는 들어가지 않는다(표시 전용)
package com.example.guardian.shared.protocol

import com.example.guardian.shared.flow.FlowMachine
import com.example.guardian.shared.flow.FlowSnapshot
import com.example.guardian.shared.flow.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowLocalTestFieldTest {
    private val snap = FlowSnapshot(powerOn = true)

    @Test fun omittedWhenFalseAndSentOnlyWhenTrue() {
        val plain = FlowStatus.of(snap, Owner.CAR)
        assertFalse(plain.localTest)
        assertFalse("거짓이면 싣지 않는다(Watch.noSignal 방식)", plain.toJson().containsKey("localTest"))
        val local = plain.copy(localTest = true)
        assertEquals(true, local.toJson()["localTest"])
        assertFalse("flow.test 는 그대로 거짓", local.test)
    }

    @Test fun roundTripAndOldBuildCompatibility() {
        val local = FlowStatus.of(snap, Owner.CAR).copy(localTest = true)
        val back = FlowStatus.fromJson(JsonObj(Json.parseObject(Json.encode(local.toJson()))))
        assertTrue(back.localTest)
        assertEquals(local.snapshot, back.snapshot)
        // 옛 판이 보낸 flow(필드 없음) → 거짓. 모르는 필드가 섞여도 읽는다
        val old = Json.parseObject(Json.encode(FlowStatus.of(snap, Owner.CAR).toJson())).toMutableMap()
        assertFalse(FlowStatus.fromJson(JsonObj(old)).localTest)
        old["someFutureField"] = 1
        assertFalse(FlowStatus.fromJsonOrNull(old)!!.localTest)
        // 넘김 · 복원 경로는 스냅숏만 쓴다 — localTest 는 상태 기계에 닿지 않는다
        val restored = FlowMachine.restore(back.restoreLocal(null, 0, sameDevice = true).snapshot)
        assertFalse(restored.snapshot().test)
    }
}
