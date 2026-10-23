package com.bitlockerdroid.service

/** Validates a newly opened session and closes it if it cannot be admitted. */
internal object SessionAdmission {
    fun <T : AutoCloseable> validateBeforePublish(
        candidate: T,
        validate: (T) -> Unit,
        publish: (T) -> Unit
    ): Result<T> {
        try {
            validate(candidate)
        } catch (validationFailure: Throwable) {
            try {
                candidate.close()
            } catch (closeFailure: Throwable) {
                if (closeFailure !== validationFailure) {
                    validationFailure.addSuppressed(closeFailure)
                }
            }
            return Result.failure(validationFailure)
        }
        publish(candidate)
        return Result.success(candidate)
    }
}
