package com.bitlockerdroid.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {

    fun wrapContext(base: Context): Context {
        val lang = PreferenceHelper.getAppLanguage(base)
        if (lang == PreferenceHelper.LANG_SYSTEM) {
            return base
        }
        val locale = when (lang) {
            "zh-CN" -> Locale.SIMPLIFIED_CHINESE
            "en" -> Locale.ENGLISH
            else -> Locale.forLanguageTag(lang)
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        val localeList = android.os.LocaleList(locale)
        android.os.LocaleList.setDefault(localeList)
        config.setLocales(localeList)
        return base.createConfigurationContext(config)
    }

    fun applyLanguage(language: String) {
        PreferenceHelper.appLanguage = language
        val localeList = when (language) {
            PreferenceHelper.LANG_ZH -> LocaleListCompat.forLanguageTags("zh-CN")
            PreferenceHelper.LANG_EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
