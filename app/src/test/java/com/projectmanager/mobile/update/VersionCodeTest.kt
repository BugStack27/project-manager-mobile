package com.projectmanager.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * versionCode 必须和 release.yml 的 major*10000 + minor*100 + patch 一致：
 * 手机端用这个值判断「Release 是否比已装的包新」，与流水线推导不一致会出现
 * 永远提示更新 / 永远不提示更新。
 */
class VersionCodeTest {

    @Test
    fun `tag with v prefix uses major minor patch formula`() {
        assertEquals(2, parseVersionCode("v0.0.2"))
        assertEquals(10203, parseVersionCode("v1.2.3"))
        assertEquals(10000, parseVersionCode("v1.0.0"))
    }

    @Test
    fun `missing trailing parts default to zero`() {
        assertEquals(10000, parseVersionCode("1"))
        assertEquals(10200, parseVersionCode("1.2"))
    }

    @Test
    fun `invalid tags are ignored`() {
        assertNull(parseVersionCode(""))
        assertNull(parseVersionCode("latest"))
        assertNull(parseVersionCode("v1.2.3.4"))
        assertNull(parseVersionCode("v1.-2.3"))
        assertNull(parseVersionCode("v1.2.x"))
    }
}
