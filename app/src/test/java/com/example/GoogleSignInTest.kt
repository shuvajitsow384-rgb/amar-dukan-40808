package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.auth.SignInRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GoogleSignInTest {

    @Test
    fun testWebClientIdConfiguration() {
        val expectedWebClientId = "645499359222-t6kmdj2nmpu6u0tvp80gccp538bf8if0.apps.googleusercontent.com"
        assertEquals(expectedWebClientId, SignInRepository.FALLBACK_WEB_CLIENT_ID)
    }

    @Test
    fun testApplicationContextPackageName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(context)
        assertEquals("com.aistudio.kalimata.store", context.packageName)
    }
}
