package io.cloak.os.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    @Test fun realPackageNamesAreAccepted() {
        listOf("com.android.vending", "io.cloak.os", "org.mozilla.firefox", "com.a_b.c1").forEach {
            assertTrue(it, Commands.isValidPackage(it))
        }
    }

    @Test fun malformedPackageNamesAreRejected() {
        listOf(
            "", "nodots", "com.", ".com.app", "com..app", "com.123", "com._x",
            "1com.app", "com.app;rm", "com/app", "com.app ", "com.app\n", "com.ap-p"
        ).forEach { assertFalse("'$it'", Commands.isValidPackage(it)) }
    }

    @Test fun overlongPackageNameIsRejected() {
        val long = "com." + "a".repeat(260)
        assertFalse(Commands.isValidPackage(long))
    }

    @Test fun onlyPackageCommandsRequirePackageArgument() {
        assertTrue(Commands.CLONE in Commands.TAKES_PACKAGE)
        assertTrue(Commands.UNINSTALL in Commands.TAKES_PACKAGE)
        assertFalse(Commands.DELETE_SPACE in Commands.TAKES_PACKAGE)
        assertFalse(Commands.SET_PRIVACY in Commands.TAKES_PACKAGE)
    }
}