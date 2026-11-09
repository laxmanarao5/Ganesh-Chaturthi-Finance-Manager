package com.vinayaka.finance

import com.vinayaka.finance.data.update.UpdateInfo
import com.vinayaka.finance.data.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class UpdateTest {
    private fun info(code: Int) = UpdateInfo(code, "1.$code.0")

    @Test fun `version file written by publishApk is understood`() {
        val text = """{"versionCode":3,"versionName":"1.3.0","apk":"vinayaka-finance.apk","sha256":"abc","size":123,"notes":"One\nTwo"}"""
        val i = UpdateLogic.parse(text)
        assertNotNull(i)
        assertEquals(3, i!!.versionCode)
        assertEquals("1.3.0", i.versionName)
        assertEquals("abc", i.sha256)
        assertEquals(123L, i.size)
        assertEquals("One\nTwo", i.notes)
    }

    @Test fun `extra fields are ignored and optional ones default`() {
        val i = UpdateLogic.parse("""{"versionCode":2,"versionName":"1.1.0","future":"x"}""")
        assertNotNull(i)
        assertEquals("vinayaka-finance.apk", i!!.apk)
        assertEquals("", i.sha256)
    }

    @Test fun `garbage or an html error page is not an update`() {
        assertNull(UpdateLogic.parse("<html>404: Not Found</html>"))
        assertNull(UpdateLogic.parse(""))
        assertNull(UpdateLogic.parse("""{"versionName":"1.0"}"""))
        assertNull(UpdateLogic.parse("""{"versionCode":0,"versionName":"0"}"""))
    }

    @Test fun `only a higher version is offered, never the same or a downgrade`() {
        assertTrue(UpdateLogic.isNewer(info(3), installedVersionCode = 2))
        assertFalse(UpdateLogic.isNewer(info(2), installedVersionCode = 2))
        assertFalse(UpdateLogic.isNewer(info(1), installedVersionCode = 2))
    }

    @Test fun `checksum compares case-insensitively and a blank one means not provided`() {
        val hash = UpdateLogic.hex(MessageDigest.getInstance("SHA-256").digest("hello".toByteArray()))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
        assertTrue(UpdateLogic.checksumOk(hash.uppercase(), hash))
        assertTrue(UpdateLogic.checksumOk("", hash))
        assertFalse(UpdateLogic.checksumOk("deadbeef", hash))
    }
}
