package com.example

import com.example.data.firestore.AppRole
import com.example.data.firestore.FirestoreUserRole
import com.example.data.firestore.PERMANENT_ADMIN_EMAILS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGateSecurityTest {

    @Test
    fun testPermanentAdminListContainsAuthorizedEmail() {
        assertTrue(
            "shuvajitsow384@gmail.com must be in the PERMANENT_ADMIN_EMAILS whitelist",
            PERMANENT_ADMIN_EMAILS.contains("shuvajitsow384@gmail.com")
        )
    }

    @Test
    fun testPermanentAdminRoleHasFullPermissions() {
        val adminRole = FirestoreUserRole.createAdmin(
            uid = "test-admin-uid",
            email = "shuvajitsow384@gmail.com",
            displayName = "Shuvajit Sow"
        )
        assertTrue(adminRole.isAdmin)
        assertTrue(adminRole.effectiveCanViewReports)
        assertTrue(adminRole.effectiveCanViewCostPrice)
        assertTrue(adminRole.effectiveCanManageInventory)
        assertTrue(adminRole.effectiveCanViewKhata)
        assertTrue(adminRole.effectiveCanManageExpenses)
        assertTrue(adminRole.effectiveCanAccessSettings)
        assertFalse(adminRole.isUnrecognized)
    }

    @Test
    fun testUnrecognizedUserRoleHasZeroAccess() {
        val unassignedRole = FirestoreUserRole.createUnassigned(
            uid = "stranger-uid",
            email = "stranger@random.com",
            displayName = "Stranger"
        )
        assertTrue(unassignedRole.isUnrecognized)
        assertFalse(unassignedRole.effectiveCanViewReports)
        assertFalse(unassignedRole.effectiveCanViewCostPrice)
        assertFalse(unassignedRole.effectiveCanManageInventory)
        assertFalse(unassignedRole.effectiveCanViewKhata)
        assertFalse(unassignedRole.effectiveCanManageExpenses)
        assertFalse(unassignedRole.effectiveCanAccessSettings)
    }
}
