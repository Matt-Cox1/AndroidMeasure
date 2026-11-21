package dev.precisionmeasure.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated guardrail enforcing AndroidMeasure's zero-data philosophy:
 * the app must never request network, location, identity, or analytics permissions.
 */
class PrivacyManifestGuardTest {

    @Test
    fun manifestContainsNoNetworkOrTrackingPermissions() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Expected AndroidManifest.xml at ${manifestFile.absolutePath}", manifestFile.exists())
        val xml = manifestFile.readText()

        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_PHONE_STATE",
            "com.google.android.gms.permission.AD_ID",
        )
        for (perm in forbidden) {
            assertFalse("Forbidden permission found in AndroidManifest.xml: $perm", xml.contains(perm))
        }
    }
}
