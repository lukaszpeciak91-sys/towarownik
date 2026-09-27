package pl.lukaszpeciak.towarownik

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

internal enum class AppLanguage(
    val languageTag: String,
    val labelRes: Int,
) {
    POLISH("pl", R.string.language_polish),
    ENGLISH("en", R.string.language_english),
}

internal val supportedAppLanguageTags: List<String> =
    AppLanguage.entries.map(AppLanguage::languageTag)

internal fun appLanguageForTag(languageTag: String?): AppLanguage =
    if (languageTag.equals(AppLanguage.ENGLISH.languageTag, ignoreCase = true)) {
        AppLanguage.ENGLISH
    } else {
        AppLanguage.POLISH
    }

internal fun localeListFor(language: AppLanguage): LocaleListCompat =
    LocaleListCompat.forLanguageTags(language.languageTag)

internal fun setApplicationLanguage(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(localeListFor(language))
}
