// 지시 허용 표 대조 시험(합동 점검 D-1) — 통합 FakeRelay 의 표가 공용 원본 contracts/guardian/command-roles.json 과 같은지
package com.example.guardian.integration

import com.example.guardian.shared.protocol.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class FakeRelayRolesTest {
    private fun rolesJson(): Map<String, Any?> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "contracts/guardian/command-roles.json").exists()) dir = dir.parentFile
        return Json.parseObject(File(dir!!, "contracts/guardian/command-roles.json").readText())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun fakeRelayTableMatchesSharedJson() {
        val j = rolesJson()
        val allow = (j["allow"] as Map<String, Map<String, List<String>>>).mapValues { (_, m) -> m.mapValues { (_, v) -> v.toSet() } }
        assertEquals(allow, FakeRelay.COMMAND_ROLES)
        assertEquals((j["testOnly"] as List<String>).toSet(), FakeRelay.TEST_ONLY_TYPES)
        assertFalse("CAR → PHONE FLOW_SNAPSHOT 은 없다", "FLOW_SNAPSHOT" in FakeRelay.COMMAND_ROLES.getValue("CAR"))
    }
}
