package com.bitlockerdroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class UnlockAuthorizationPolicyTest {
    private data class AutomaticUnlockInputs(
        val vaultProtected: Boolean,
        val globalAutoUnlock: Boolean,
        val volumeAutoUnlock: Boolean,
        val manuallyLocked: Boolean,
        val closing: Boolean,
    )

    @Test
    fun automaticUnlockReadsOnlyForTheSingleFullyAllowedCombination() {
        val deniedOrAllowed = listOf(false, true)
        val allowedInputs = AutomaticUnlockInputs(
            vaultProtected = false,
            globalAutoUnlock = true,
            volumeAutoUnlock = true,
            manuallyLocked = false,
            closing = false,
        )
        val credential = Any()

        for (vaultProtected in deniedOrAllowed) {
            for (globalAutoUnlock in deniedOrAllowed) {
                for (volumeAutoUnlock in deniedOrAllowed) {
                    for (manuallyLocked in deniedOrAllowed) {
                        for (closing in deniedOrAllowed) {
                            val inputs = AutomaticUnlockInputs(
                                vaultProtected = vaultProtected,
                                globalAutoUnlock = globalAutoUnlock,
                                volumeAutoUnlock = volumeAutoUnlock,
                                manuallyLocked = manuallyLocked,
                                closing = closing,
                            )
                            val expectedAllowed = inputs == allowedInputs
                            val message = "Unexpected decision for $inputs"

                            assertEquals(
                                message,
                                expectedAllowed,
                                UnlockAuthorizationPolicy.canUseRememberedCredential(
                                    vaultProtected,
                                    globalAutoUnlock,
                                    volumeAutoUnlock,
                                    manuallyLocked,
                                    closing,
                                ),
                            )

                            var readCount = 0
                            val result = UnlockAuthorizationPolicy.forAutomaticUnlock(
                                vaultProtected,
                                globalAutoUnlock,
                                volumeAutoUnlock,
                                manuallyLocked,
                                closing,
                            ) {
                                readCount += 1
                                credential
                            }

                            assertEquals("$message; credential reads", if (expectedAllowed) 1 else 0, readCount)
                            if (expectedAllowed) {
                                assertSame(message, credential, result)
                            } else {
                                assertNull(message, result)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun prefillReadsOnlyWhenTheVaultIsUnprotected() {
        for (vaultProtected in listOf(false, true)) {
            val expectedAllowed = !vaultProtected
            assertEquals(
                "vaultProtected=$vaultProtected",
                expectedAllowed,
                UnlockAuthorizationPolicy.canPrefillRememberedCredential(vaultProtected),
            )

            var readCount = 0
            val credential = Any()
            val result = UnlockAuthorizationPolicy.forPrefill(vaultProtected) {
                readCount += 1
                credential
            }

            assertEquals("vaultProtected=$vaultProtected; credential reads", if (expectedAllowed) 1 else 0, readCount)
            if (expectedAllowed) {
                assertSame(credential, result)
            } else {
                assertNull(result)
            }
        }
    }
}
