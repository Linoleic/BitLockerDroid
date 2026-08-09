package com.bitlockerdroid.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bitlockerdroid.R
import com.bitlockerdroid.service.UnlockManager
import com.bitlockerdroid.util.LogFile
import java.util.regex.Pattern

/**
 * Shown when a BitLocker volume is inserted: asks for the user password (or a
 * 48-digit recovery key) and unlocks the volume.
 */
class UnlockDialogActivity : Activity() {

    companion object {
        const val EXTRA_DEVICE_PATH = "device_path"
        const val EXTRA_OFFSET = "offset"
        private val RECOVERY_PATTERN = Pattern.compile("^(\\d{6}-){7}\\d{6}$")
    }

    private var devicePath: String? = null
    private var offset: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        devicePath = intent.getStringExtra(EXTRA_DEVICE_PATH)
        offset = intent.getLongExtra(EXTRA_OFFSET, 0)

        val path = devicePath ?: run {
            finish()
            return
        }

        val input = EditText(this).apply {
            hint = getString(R.string.password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val remember = CheckBox(this).apply {
            text = getString(R.string.remember_password)
        }
        val autoUnlock = CheckBox(this).apply {
            text = getString(R.string.auto_unlock)
            isChecked = com.bitlockerdroid.util.PreferenceHelper.autoUnlockRemembered
        }
        val useRecovery = CheckBox(this).apply {
            text = getString(R.string.use_recovery_key)
        }

        useRecovery.setOnCheckedChangeListener { _, checked ->
            input.hint = if (checked) getString(R.string.recovery_key_hint) else getString(R.string.password_hint)
            input.inputType = if (checked) {
                android.text.InputType.TYPE_CLASS_TEXT
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.dialog_padding)
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@UnlockDialogActivity).apply {
                text = getString(R.string.unlock_prompt, path)
                textSize = 14f
            })
            addView(input)
            addView(remember)
            addView(autoUnlock)
            addView(useRecovery)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.unlock_title)
            .setView(column)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                finish()
            }
            .setPositiveButton(R.string.unlock_action, null)
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString()
                    val rememberFlag = remember.isChecked
                    val recovery = useRecovery.isChecked

                    if (value.isEmpty()) {
                        Toast.makeText(this, R.string.password_required, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val result = if (recovery) {
                        if (!RECOVERY_PATTERN.matcher(value).matches()) {
                            Toast.makeText(this, R.string.invalid_recovery_key, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        unlockWithRecovery(path, value)
                    } else {
                        // Persist the auto-unlock preference regardless of whether
                        // this particular unlock remembers the password.
                        com.bitlockerdroid.util.PreferenceHelper.autoUnlockRemembered = autoUnlock.isChecked
                        unlockWithPassword(path, value, rememberFlag)
                    }

                    if (result) {
                        Toast.makeText(this, R.string.unlock_success, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        // Show the real error message from the unlock attempt.
                        Toast.makeText(this, lastError ?: getString(R.string.unlock_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
    }

    private var lastError: String? = null

    private fun unlockWithPassword(path: String, password: String, remember: Boolean): Boolean {
        val result = UnlockManager.unlockWithPassword(this, path, offset, password, remember)
        if (result.isFailure) {
            lastError = result.exceptionOrNull()?.message
            LogFile.write("app", "unlock password failed: $lastError")
        }
        return result.isSuccess
    }

    private fun unlockWithRecovery(path: String, key: String): Boolean {
        return try {
            val core = com.bitlockerdroid.service.DislockerCore.openWithRecoveryKey(path, offset, key)
            UnlockManager.registerDirect(core, this)
            lastError = null
            true
        } catch (e: Exception) {
            lastError = e.message
            LogFile.write("app", "unlock recovery failed: $lastError")
            false
        }
    }
}
