package com.napreth.set4u.core.viewmodel

import com.napreth.set4u.core.session.CoreSession
import com.napreth.set4u.core.session.SettingTarget

class ViewModelFactory internal constructor(private val session: CoreSession) {
    fun settings(target: SettingTarget, pageId: String, locale: String = session.catalog.texts.defaultLocale): SettingsViewModel {
        session.requireOpen()
        return SettingsViewModel(session, target, pageId, locale)
    }
}
