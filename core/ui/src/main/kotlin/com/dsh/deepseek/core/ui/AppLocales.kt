package com.dsh.deepseek.core.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.dsh.deepseek.core.datastore.Language

/** 按应用语言：写入 AppCompat per-app locales，触发 Activity 重建以加载对应 string 资源。 */
object AppLocales {
    fun apply(language: Language) {
        val locales = when (language) {
            Language.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            Language.ZH -> LocaleListCompat.forLanguageTags("zh-CN")
            Language.EN -> LocaleListCompat.forLanguageTags("en")
        }
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (current != locales.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
