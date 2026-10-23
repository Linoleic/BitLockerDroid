package com.bitlockerdroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SavedCredentialPersistenceTest {
    private data class StoredCredential(
        val ciphertext: String,
        val displayName: String,
        val isRecoveryKey: Boolean,
        val autoUnlock: Boolean,
    )

    @Test
    fun unspecifiedChoicePreservesCiphertextAndMetadataWithoutEncryptingOrDeleting() {
        val original = StoredCredential("old-ciphertext", "Archive", true, true)
        var stored: StoredCredential? = original
        var encryptCalls = 0
        var deleteCalls = 0

        SavedCredentialPersistence.update(
            remember = null,
            save = {
                encryptCalls++
                stored = StoredCredential("new-ciphertext", "Changed", false, false)
            },
            forget = {
                deleteCalls++
                stored = null
            },
        )

        assertEquals(original, stored)
        assertEquals(0, encryptCalls)
        assertEquals(0, deleteCalls)
    }

    @Test
    fun trueRunsOnlyTheSaveAction() {
        var stored: StoredCredential? = StoredCredential("old-ciphertext", "Archive", true, true)
        var saveCalls = 0
        var forgetCalls = 0

        SavedCredentialPersistence.update(
            remember = true,
            save = {
                saveCalls++
                stored = StoredCredential("new-ciphertext", "Backup", false, false)
            },
            forget = {
                forgetCalls++
                stored = null
            },
        )

        assertEquals(StoredCredential("new-ciphertext", "Backup", false, false), stored)
        assertEquals(1, saveCalls)
        assertEquals(0, forgetCalls)
    }

    @Test
    fun falseRunsOnlyTheForgetAction() {
        var stored: StoredCredential? = StoredCredential("old-ciphertext", "Archive", true, true)
        var saveCalls = 0
        var forgetCalls = 0

        SavedCredentialPersistence.update(
            remember = false,
            save = {
                saveCalls++
                stored = StoredCredential("unexpected-ciphertext", "Unexpected", false, false)
            },
            forget = {
                forgetCalls++
                stored = null
            },
        )

        assertEquals(null, stored)
        assertEquals(0, saveCalls)
        assertEquals(1, forgetCalls)
    }

    @Test
    fun saveFailurePropagatesWithoutFallingBackToForget() {
        val failure = IllegalStateException("save failed")
        var forgetCalls = 0

        val thrown = assertThrows(IllegalStateException::class.java) {
            SavedCredentialPersistence.update(
                remember = true,
                save = { throw failure },
                forget = { forgetCalls++ },
            )
        }

        assertSame(failure, thrown)
        assertEquals(0, forgetCalls)
    }

    @Test
    fun forgetFailurePropagatesWithoutFallingBackToSave() {
        val failure = IllegalStateException("delete failed")
        var saveCalls = 0

        val thrown = assertThrows(IllegalStateException::class.java) {
            SavedCredentialPersistence.update(
                remember = false,
                save = { saveCalls++ },
                forget = { throw failure },
            )
        }

        assertSame(failure, thrown)
        assertEquals(0, saveCalls)
    }
}
